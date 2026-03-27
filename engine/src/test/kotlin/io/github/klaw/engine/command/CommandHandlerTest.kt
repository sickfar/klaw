package io.github.klaw.engine.command

import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.FilesConfig
import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.context.SkillDetail
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.context.SkillValidationEntry
import io.github.klaw.engine.context.SkillValidationReport
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.workspace.HeartbeatRunnerFactory
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Clock

class CommandHandlerTest {
    @TempDir
    lateinit var workspace: Path

    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val messageRepository = mockk<MessageRepository>(relaxed = true)
    private val heartbeatRunnerFactory = mockk<HeartbeatRunnerFactory>(relaxed = true)
    private val skillRegistry = mockk<SkillRegistry>(relaxed = true)

    private val defaultModels = mapOf("test/model" to ModelConfig())

    private fun makeConfig(models: Map<String, ModelConfig> = defaultModels) =
        EngineConfig(
            providers = mapOf("test" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost")),
            models = models,
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
                ),
            context = ContextConfig(tokenBudget = 4096, subagentHistory = 5),
            processing = ProcessingConfig(debounceMs = 100, maxConcurrentLlm = 2, maxToolCallRounds = 5),
            httpRetry =
                HttpRetryConfig(
                    maxRetries = 1,
                    requestTimeoutMs = 5000,
                    initialBackoffMs = 100,
                    backoffMultiplier = 2.0,
                ),
            logging = LoggingConfig(subagentConversations = false),
            codeExecution =
                CodeExecutionConfig(
                    dockerImage = "python:3.12-slim",
                    timeout = 30,
                    allowNetwork = false,
                    maxMemory = "256m",
                    maxCpus = "1.0",
                    keepAlive = false,
                    keepAliveIdleTimeoutMin = 5,
                    keepAliveMaxExecutions = 100,
                ),
            files = FilesConfig(maxFileSizeBytes = 10485760L),
        )

    private fun makeSession(model: String = "test/model") =
        Session(
            chatId = "chat-1",
            model = model,
            segmentStart = Clock.System.now().toString(),
            createdAt = Clock.System.now(),
        )

    private fun makeHandler(models: Map<String, ModelConfig> = defaultModels) =
        CommandHandler(
            sessionManager,
            messageRepository,
            makeConfig(models),
            jakarta.inject.Provider { heartbeatRunnerFactory },
            skillRegistry,
        ).also { it.workspacePath = workspace }

    private fun makeCmd(
        command: String,
        args: String? = null,
    ) = CommandSocketMessage(
        channel = "telegram",
        chatId = "chat-1",
        command = command,
        args = args,
    )

    @Test
    fun `slash_new resets segment and returns confirmation`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("new"), session)

            coVerify { messageRepository.appendSessionBreak("chat-1") }
            coVerify { sessionManager.resetSegment("chat-1") }
            assertTrue(result.contains("started", ignoreCase = true) || result.contains("New", ignoreCase = true))
        }

    @Test
    fun `slash_model without args shows current model`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession(model = "test/model")

            val result = handler.handle(makeCmd("model"), session)

            assertTrue(result.contains("test/model"), "Response should contain current model name, got: $result")
        }

    @Test
    fun `slash_model with valid model switches model`() =
        runTest {
            val models =
                mapOf(
                    "test/model" to ModelConfig(),
                    "other/model" to ModelConfig(),
                )
            val handler = makeHandler(models)
            val session = makeSession(model = "test/model")

            val result = handler.handle(makeCmd("model", "other/model"), session)

            coVerify { sessionManager.updateModel("chat-1", "other/model") }
            assertTrue(result.contains("Switched", ignoreCase = true), "Response should confirm switch, got: $result")
            assertTrue(result.contains("other/model"), "Response should contain new model name, got: $result")
        }

    @Test
    fun `slash_model with invalid model returns error`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("model", "nonexistent/model"), session)

            assertTrue(
                result.contains("Unknown", ignoreCase = true),
                "Response should indicate unknown model, got: $result",
            )
            assertTrue(result.contains("test/model"), "Response should list available models, got: $result")
        }

    @Test
    fun `slash_models lists all configured models`() =
        runTest {
            val models =
                mapOf(
                    "glm/glm-5" to ModelConfig(),
                    "deepseek/deepseek-chat" to ModelConfig(),
                )
            val handler = makeHandler(models)
            val session = makeSession()

            val result = handler.handle(makeCmd("models"), session)

            assertTrue(result.contains("glm/glm-5"), "Response should list glm/glm-5, got: $result")
            assertTrue(
                result.contains("deepseek/deepseek-chat"),
                "Response should list deepseek/deepseek-chat, got: $result",
            )
        }

    @Test
    fun `slash_status returns session info and context usage`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession(model = "test/model")

            val result = handler.handle(makeCmd("status"), session)

            assertTrue(result.contains("chat-1"), "Response should contain chatId, got: $result")
            assertTrue(result.contains("test/model"), "Response should contain model name, got: $result")
            assertTrue(result.contains("Context:"), "Response should contain context usage, got: $result")
            assertTrue(result.contains("tokens"), "Response should contain token info, got: $result")
            assertTrue(
                result.contains("Segment total:"),
                "Response should contain segment total, got: $result",
            )
        }

    @Test
    fun `slash_memory shows MEMORY_md content`() =
        runTest {
            Files.writeString(workspace.resolve("MEMORY.md"), "Important facts about user.")
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("memory"), session)

            assertTrue(
                result.contains("Important facts about user."),
                "Response should contain MEMORY.md content, got: $result",
            )
        }

    @Test
    fun `use_for_heartbeat returns disabled when runner is null`() =
        runTest {
            every { heartbeatRunnerFactory.runner } returns null
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("use-for-heartbeat"), session)

            assertTrue(result.contains("disabled", ignoreCase = true), "Should indicate disabled, got: $result")
        }

    @Test
    fun `use_for_heartbeat updates runner delivery target`() =
        runTest {
            val runner =
                io.github.klaw.engine.workspace.HeartbeatRunner(
                    config = makeConfig(),
                    chat = { _, _ -> error("not called") },
                    toolExecutor = mockk(),
                    getOrCreateSession = { _, _ -> error("not called") },
                    workspaceLoader = mockk(),
                    toolRegistry = mockk(),
                    pushToGateway = {},
                    workspacePath = workspace,
                    maxToolCallRounds = 5,
                )
            every { heartbeatRunnerFactory.runner } returns runner
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("use-for-heartbeat"), session)

            assertTrue(result.contains("telegram"), "Should confirm channel, got: $result")
            assertTrue(result.contains("chat-1"), "Should confirm chatId, got: $result")
            assertEquals("telegram", runner.deliveryChannel)
            assertEquals("chat-1", runner.deliveryChatId)
        }

    @Test
    fun `slash_memory returns message when MEMORY_md missing`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("memory"), session)

            assertTrue(result.contains("No MEMORY.md"), "Response should indicate missing file, got: $result")
        }

    @Test
    fun `skills validate returns human readable report`() =
        runTest {
            val report =
                SkillValidationReport(
                    listOf(
                        SkillValidationEntry("good-skill", "good-skill", "workspace", true),
                        SkillValidationEntry(null, "broken", "data", false, "missing SKILL.md"),
                    ),
                )
            coEvery { skillRegistry.validate() } returns report
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("skills", "validate"), session)

            assertTrue(result.contains("good-skill"), "Should contain valid skill name, got: $result")
            assertTrue(result.contains("valid"), "Should contain 'valid' marker, got: $result")
            assertTrue(result.contains("broken"), "Should contain broken skill dir, got: $result")
            assertTrue(result.contains("missing SKILL.md"), "Should contain error message, got: $result")
            assertTrue(result.contains("2 skills checked"), "Should contain total count, got: $result")
            assertTrue(result.contains("1 error"), "Should contain error count, got: $result")
        }

    @Test
    fun `skills list returns formatted list`() =
        runTest {
            val details =
                listOf(
                    SkillDetail("alpha", "Alpha skill", "workspace"),
                    SkillDetail("beta", "Beta skill", "data"),
                    SkillDetail("scheduling", "Schedule management", "bundled"),
                )
            coEvery { skillRegistry.listDetailed() } returns details
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("skills", "list"), session)

            io.mockk.verify { skillRegistry.discover() }
            assertTrue(result.contains("Loaded skills"), "Should contain header, got: $result")
            assertTrue(result.contains("alpha"), "Should contain skill name, got: $result")
            assertTrue(result.contains("Alpha skill"), "Should contain description, got: $result")
            assertTrue(result.contains("(workspace)"), "Should contain source label, got: $result")
            assertTrue(result.contains("(data)"), "Should contain data source, got: $result")
            assertTrue(result.contains("(bundled)"), "Should contain bundled source, got: $result")
        }

    @Test
    fun `skills list with no skills returns empty message`() =
        runTest {
            coEvery { skillRegistry.listDetailed() } returns emptyList()
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("skills", "list"), session)

            assertTrue(result.contains("No skills"), "Should indicate no skills, got: $result")
        }

    @Test
    fun `skills without args returns usage with list`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("skills"), session)

            assertTrue(result.contains("Usage"), "Should return usage info, got: $result")
            assertTrue(result.contains("list"), "Should mention list subcommand, got: $result")
            assertTrue(result.contains("validate"), "Should mention validate subcommand, got: $result")
        }

    @Test
    fun `skills unknown subcommand returns usage with list`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("skills", "foo"), session)

            assertTrue(result.contains("Usage"), "Should return usage info, got: $result")
            assertTrue(result.contains("list"), "Should mention list subcommand, got: $result")
        }

    @Test
    fun `help includes skills list`() =
        runTest {
            val handler = makeHandler()
            val session = makeSession()

            val result = handler.handle(makeCmd("help"), session)

            assertTrue(result.contains("/skills list"), "Help should mention /skills list, got: $result")
        }
}
