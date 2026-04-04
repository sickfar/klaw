package io.github.klaw.engine.context

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AgentConfig
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.DocsConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.FilesConfig
import io.github.klaw.common.config.HostExecutionConfig
import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.SkillsConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.config.VisionConfig
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.ImageUrlContentPart
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TextContentPart
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.message.AttachmentMetadata
import io.github.klaw.engine.message.AttachmentRef
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.tools.ContextStatus
import io.github.klaw.engine.tools.EngineHealthProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Instant

class ContextBuilderVisionTest {
    private lateinit var db: KlawDatabase
    private lateinit var messageRepository: MessageRepository

    private val workspaceLoader = mockk<WorkspaceLoader>()
    private val summaryService = mockk<SummaryService>()
    private val skillRegistry = mockk<SkillRegistry>()
    private val autoRagService = mockk<AutoRagService>()
    private val subagentHistoryLoader = mockk<SubagentHistoryLoader>()
    private val healthProvider = mockk<EngineHealthProvider>()
    private val llmRouter = mockk<LlmRouter>()

    @TempDir
    lateinit var tempDir: Path

    private fun buildConfig(
        visionConfig: VisionConfig = VisionConfig(enabled = true, model = "test/vision-model"),
    ): EngineConfig =
        EngineConfig(
            providers = mapOf("test" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost")),
            models =
                mapOf(
                    "test/model" to ModelConfig(),
                ),
            routing =
                RoutingConfig(
                    default = "test/model",
                    fallback = emptyList(),
                    tasks = TaskRoutingConfig(summarization = "test/model", subagent = "test/model"),
                ),
            memory =
                MemoryConfig(
                    embedding = EmbeddingConfig(type = "onnx", model = "all-MiniLM-L6-v2"),
                    chunking = ChunkingConfig(size = 512, overlap = 64),
                    search = SearchConfig(topK = 10),
                    autoRag = AutoRagConfig(enabled = false),
                ),
            context = ContextConfig(tokenBudget = 4096, subagentHistory = 5),
            processing = ProcessingConfig(debounceMs = 100, maxConcurrentLlm = 2, maxToolCallRounds = 5),
            httpRetry = HttpRetryConfig(),
            logging = LoggingConfig(),
            codeExecution = CodeExecutionConfig(),
            files = FilesConfig(),
            commands = emptyList(),
            agents = mapOf("default" to AgentConfig(workspace = "/tmp/klaw-test-workspace")),
            skills = SkillsConfig(),
            docs = DocsConfig(),
            hostExecution = HostExecutionConfig(),
            vision = visionConfig,
        )

    private fun buildSession(
        chatId: String = "chat-1",
        model: String = "test/model",
        segmentStart: String = "2024-01-01T00:00:00Z",
    ) = Session(
        chatId = chatId,
        model = model,
        segmentStart = segmentStart,
        createdAt = Instant.parse("2024-01-01T00:00:00Z"),
    )

    private fun buildContextBuilder(config: EngineConfig): ContextBuilder {
        val builder =
            ContextBuilder(
                workspaceLoader = workspaceLoader,
                messageRepository = messageRepository,
                summaryService = summaryService,
                skillRegistry = skillRegistry,
                toolRegistry =
                    io.github.klaw.engine.context.stubs
                        .StubToolRegistry(),
                config = config,
                autoRagService = autoRagService,
                subagentHistoryLoader = subagentHistoryLoader,
                healthProviderLazy = { healthProvider },
                llmRouter = llmRouter,
            )
        builder.overrideAllowedImageDirs(listOf(tempDir))
        return builder
    }

    @BeforeEach
    fun setUp() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        db = KlawDatabase(driver)
        messageRepository = MessageRepository(db)

        coEvery { workspaceLoader.loadSystemPrompt() } returns ""
        coEvery { workspaceLoader.loadMemorySummary() } returns null
        coEvery { summaryService.getSummariesForContext(any(), any(), any()) } returns
            SummaryContextResult(emptyList(), null, false)
        coEvery { skillRegistry.listAll() } returns emptyList()
        io.mockk.every { skillRegistry.discover() } returns Unit
        coEvery { autoRagService.search(any(), any(), any(), any(), any()) } returns emptyList()
        coEvery { subagentHistoryLoader.loadHistory(any(), any()) } returns emptyList()
        coEvery { healthProvider.getContextStatus() } returns
            ContextStatus(
                gatewayConnected = true,
                uptime = java.time.Duration.ofHours(1),
                scheduledJobs = 0,
                activeSessions = 0,
                sandboxReady = true,
                embeddingType = "onnx",
                docker = false,
            )
    }

    @Test
    fun `message with attachments and vision-capable model produces contentParts`() =
        runTest {
            // gpt-4o is registered with image=true in model-registry.json
            val config = buildConfig()
            val contextBuilder = buildContextBuilder(config)

            // Create a test image file
            val imagePath = tempDir.resolve("photo.png")
            Files.write(imagePath, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) // PNG header bytes

            val metadata =
                AttachmentMetadata(
                    attachments = listOf(AttachmentRef(path = imagePath.toString(), mimeType = "image/png")),
                )

            // Save a multimodal message in DB
            messageRepository.save(
                id = "msg-1",
                channel = "telegram",
                chatId = "chat-1",
                role = "user",
                type = "multimodal",
                content = "What is in this image?",
                metadata = Json.encodeToString(metadata),
                tokens = 10,
            )

            // Use a model known to support images: gpt-4o
            val visionSession = buildSession(model = "openai/gpt-4o")
            val result = contextBuilder.buildContext(visionSession, emptyList(), isSubagent = false)

            // Find the user message (skip system messages)
            val userMsg = result.messages.find { it.role == "user" }
            assertNotNull(userMsg, "Expected a user message in context")
            assertNotNull(userMsg!!.contentParts, "Expected contentParts for multimodal message")

            val textParts = userMsg.contentParts!!.filterIsInstance<TextContentPart>()
            val imageParts = userMsg.contentParts!!.filterIsInstance<ImageUrlContentPart>()

            assertEquals(1, textParts.size, "Expected 1 text part")
            assertEquals("What is in this image?", textParts[0].text)
            assertEquals(1, imageParts.size, "Expected 1 image part")
            assertTrue(imageParts[0].imageUrl.url.startsWith("data:image/png;base64,"))
        }

    @Test
    fun `message with attachments and non-vision model calls auto-describe`() =
        runTest {
            val config = buildConfig()
            val contextBuilder = buildContextBuilder(config)

            val imagePath = tempDir.resolve("photo.jpg")
            Files.write(imagePath, byteArrayOf(0xFF.toByte(), 0xD8.toByte())) // JPEG header

            val metadata =
                AttachmentMetadata(
                    attachments = listOf(AttachmentRef(path = imagePath.toString(), mimeType = "image/jpeg")),
                )

            messageRepository.save(
                id = "msg-1",
                channel = "telegram",
                chatId = "chat-1",
                role = "user",
                type = "multimodal",
                content = "Look at this",
                metadata = Json.encodeToString(metadata),
                tokens = 10,
            )

            // Mock LLM router for vision auto-describe
            coEvery { llmRouter.chat(any(), eq("test/vision-model")) } returns
                LlmResponse(
                    content = "A photograph of a cat",
                    toolCalls = null,
                    usage = TokenUsage(100, 20, 120),
                    finishReason = FinishReason.STOP,
                )

            // test/model does not exist in model-registry.json so supportsImage=false
            val session = buildSession(model = "test/model")
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val userMsg = result.messages.find { it.role == "user" }
            assertNotNull(userMsg)
            // Should have text content with description prepended, no contentParts
            assertNull(userMsg!!.contentParts, "Non-vision model should not get contentParts")
            assertTrue(
                userMsg.content!!.contains("A photograph of a cat"),
                "Expected auto-describe text in content",
            )
            assertTrue(
                userMsg.content!!.contains("Look at this"),
                "Expected original text preserved",
            )

            coVerify(exactly = 1) { llmRouter.chat(any(), "test/vision-model") }
        }

    @Test
    fun `message without attachments unchanged behavior`() =
        runTest {
            val config = buildConfig()
            val contextBuilder = buildContextBuilder(config)

            messageRepository.save(
                id = "msg-1",
                channel = "telegram",
                chatId = "chat-1",
                role = "user",
                type = "text",
                content = "Hello there",
                tokens = 5,
            )

            val session = buildSession()
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val userMsg = result.messages.find { it.role == "user" }
            assertNotNull(userMsg)
            assertEquals("Hello there", userMsg!!.content)
            assertNull(userMsg.contentParts, "Regular text message should not have contentParts")
        }

    @Test
    fun `multiple images in one message are all processed for vision model`() =
        runTest {
            val config = buildConfig()
            val contextBuilder = buildContextBuilder(config)

            val img1 = tempDir.resolve("img1.png")
            val img2 = tempDir.resolve("img2.jpg")
            val img3 = tempDir.resolve("img3.webp")
            Files.write(img1, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
            Files.write(img2, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
            Files.write(img3, byteArrayOf(0x52, 0x49, 0x46, 0x46))

            val metadata =
                AttachmentMetadata(
                    attachments =
                        listOf(
                            AttachmentRef(path = img1.toString(), mimeType = "image/png"),
                            AttachmentRef(path = img2.toString(), mimeType = "image/jpeg"),
                            AttachmentRef(path = img3.toString(), mimeType = "image/webp"),
                        ),
                )

            messageRepository.save(
                id = "msg-1",
                channel = "telegram",
                chatId = "chat-1",
                role = "user",
                type = "multimodal",
                content = "Describe all these",
                metadata = Json.encodeToString(metadata),
                tokens = 10,
            )

            val session = buildSession(model = "openai/gpt-4o")
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val userMsg = result.messages.find { it.role == "user" }
            assertNotNull(userMsg)
            val imageParts = userMsg!!.contentParts!!.filterIsInstance<ImageUrlContentPart>()
            assertEquals(3, imageParts.size, "All 3 images should be included")
        }

    @Test
    fun `image file not found on disk is gracefully skipped`() =
        runTest {
            val config = buildConfig()
            val contextBuilder = buildContextBuilder(config)

            val metadata =
                AttachmentMetadata(
                    attachments =
                        listOf(
                            AttachmentRef(path = "/nonexistent/path/photo.png", mimeType = "image/png"),
                        ),
                )

            messageRepository.save(
                id = "msg-1",
                channel = "telegram",
                chatId = "chat-1",
                role = "user",
                type = "multimodal",
                content = "Check this",
                metadata = Json.encodeToString(metadata),
                tokens = 5,
            )

            val session = buildSession(model = "openai/gpt-4o")
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val userMsg = result.messages.find { it.role == "user" }
            assertNotNull(userMsg)
            // Should fall back to text-only since no images could be read
            assertEquals("Check this", userMsg!!.content)
        }

    @Test
    fun `auto-describe caches result in DB metadata and second build skips vision call`() =
        runTest {
            val config = buildConfig()
            val contextBuilder = buildContextBuilder(config)

            val imagePath = tempDir.resolve("cached.jpg")
            Files.write(imagePath, byteArrayOf(0xFF.toByte(), 0xD8.toByte()))

            val metadata =
                AttachmentMetadata(
                    attachments = listOf(AttachmentRef(path = imagePath.toString(), mimeType = "image/jpeg")),
                )

            messageRepository.save(
                id = "msg-cache",
                channel = "telegram",
                chatId = "chat-1",
                role = "user",
                type = "multimodal",
                content = "Describe",
                metadata = Json.encodeToString(metadata),
                tokens = 5,
            )

            coEvery { llmRouter.chat(any(), eq("test/vision-model")) } returns
                LlmResponse(
                    content = "Cached description of image",
                    toolCalls = null,
                    usage = TokenUsage(100, 20, 120),
                    finishReason = FinishReason.STOP,
                )

            val session = buildSession(model = "test/model")

            // First build: should call vision model
            contextBuilder.buildContext(session, emptyList(), isSubagent = false)
            coVerify(exactly = 1) { llmRouter.chat(any(), "test/vision-model") }

            // Second build: should NOT call vision model again (cached)
            contextBuilder.buildContext(session, emptyList(), isSubagent = false)
            coVerify(exactly = 1) { llmRouter.chat(any(), "test/vision-model") }
        }

    @Test
    fun `maxImagesPerMessage limit restricts processed images`() =
        runTest {
            val config =
                buildConfig(
                    visionConfig = VisionConfig(enabled = true, model = "test/vision-model", maxImagesPerMessage = 2),
                )
            val contextBuilder = buildContextBuilder(config)

            val images =
                (1..6).map { i ->
                    val img = tempDir.resolve("img$i.png")
                    Files.write(img, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))
                    AttachmentRef(path = img.toString(), mimeType = "image/png")
                }

            val metadata = AttachmentMetadata(attachments = images)

            messageRepository.save(
                id = "msg-1",
                channel = "telegram",
                chatId = "chat-1",
                role = "user",
                type = "multimodal",
                content = "Many images",
                metadata = Json.encodeToString(metadata),
                tokens = 10,
            )

            val session = buildSession(model = "openai/gpt-4o")
            val result = contextBuilder.buildContext(session, emptyList(), isSubagent = false)

            val userMsg = result.messages.find { it.role == "user" }
            assertNotNull(userMsg)
            val imageParts = userMsg!!.contentParts!!.filterIsInstance<ImageUrlContentPart>()
            assertEquals(2, imageParts.size, "Only maxImagesPerMessage=2 images should be processed")
        }
}
