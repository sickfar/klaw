package io.github.klaw.engine.workspace

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.HeartbeatConfig
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.common.llm.ToolResult
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.context.WorkspaceLoader
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.tools.ChatContext
import io.github.klaw.engine.tools.ToolExecutor
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.time.Clock

class HeartbeatRunnerTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var conversationsDir: Path

    private val defaultHeartbeat =
        HeartbeatConfig(interval = "PT1H", channel = "telegram", injectInto = "chat123")
    private val defaultModel = "test/test-model"
    private val testSession =
        Session(chatId = "heartbeat", model = defaultModel, segmentStart = "", createdAt = Clock.System.now())

    private fun buildConfig(heartbeat: HeartbeatConfig = HeartbeatConfig()): EngineConfig =
        EngineConfig(
            providers = emptyMap(),
            models = emptyMap(),
            routing =
                io.github.klaw.common.config.RoutingConfig(
                    default = defaultModel,
                    fallback = emptyList(),
                    tasks =
                        io.github.klaw.common.config.TaskRoutingConfig(
                            summarization = defaultModel,
                            subagent = defaultModel,
                        ),
                ),
            memory =
                io.github.klaw.common.config.MemoryConfig(
                    embedding =
                        io.github.klaw.common.config
                            .EmbeddingConfig(type = "onnx", model = "test"),
                    chunking =
                        io.github.klaw.common.config
                            .ChunkingConfig(size = 512, overlap = 64),
                    search =
                        io.github.klaw.common.config
                            .SearchConfig(topK = 10),
                ),
            context =
                io.github.klaw.common.config.ContextConfig(
                    tokenBudget = 4096,
                    subagentHistory = 5,
                ),
            processing =
                io.github.klaw.common.config.ProcessingConfig(
                    debounceMs = 100,
                    maxConcurrentLlm = 2,
                    maxToolCallRounds = 5,
                ),
            heartbeat = heartbeat,
        )

    @Test
    fun `parseInterval returns null for off`() {
        assertNull(HeartbeatRunner.parseInterval("off"))
    }

    @Test
    fun `parseInterval returns null for blank`() {
        assertNull(HeartbeatRunner.parseInterval(""))
    }

    @Test
    fun `parseInterval parses ISO duration`() {
        val duration = HeartbeatRunner.parseInterval("PT1H")
        assertNotNull(duration)
        assertEquals(3600_000L, duration!!.toMillis())
    }

    @Test
    fun `parseInterval parses 30 minute duration`() {
        val duration = HeartbeatRunner.parseInterval("PT30M")
        assertNotNull(duration)
        assertEquals(1800_000L, duration!!.toMillis())
    }

    @Test
    fun `parseInterval returns null for invalid`() {
        assertNull(HeartbeatRunner.parseInterval("invalid"))
    }

    @Test
    fun `executeHeartbeat skips when HEARTBEAT_md missing`() =
        runBlocking {
            val config = buildConfig(defaultHeartbeat)
            val llmCalls = mutableListOf<LlmRequest>()
            val runner = createRunner(config, workspace, llmCalls)

            runner.executeHeartbeat()

            assertTrue(llmCalls.isEmpty())
        }

    @Test
    fun `executeHeartbeat skips when HEARTBEAT_md is blank`() =
        runBlocking {
            Files.writeString(workspace.resolve("HEARTBEAT.md"), "   \n  ")
            val config = buildConfig(defaultHeartbeat)
            val llmCalls = mutableListOf<LlmRequest>()
            val runner = createRunner(config, workspace, llmCalls)

            runner.executeHeartbeat()

            assertTrue(llmCalls.isEmpty())
        }

    @Test
    fun `executeHeartbeat runs LLM with HEARTBEAT_md content`() =
        runBlocking {
            Files.writeString(workspace.resolve("HEARTBEAT.md"), "Check the weather forecast")
            val config = buildConfig(defaultHeartbeat)
            val llmCalls = mutableListOf<LlmRequest>()
            val runner = createRunner(config, workspace, llmCalls)

            runner.executeHeartbeat()

            assertEquals(1, llmCalls.size)
            val messages = llmCalls[0].messages
            val systemMsg = messages.first { it.role == "system" }
            assertTrue(systemMsg.content!!.contains("heartbeat_deliver"))
            assertTrue(systemMsg.content!!.contains("Heartbeat Run"))
            assertTrue(messages.any { it.role == "user" && it.content?.contains("Check the weather forecast") == true })
        }

    @Test
    fun `executeHeartbeat uses configured model`() =
        runBlocking {
            Files.writeString(workspace.resolve("HEARTBEAT.md"), "Check something")
            val config = buildConfig(defaultHeartbeat.copy(model = "custom/model"))
            var requestedModel: String? = null
            val llmCalls = mutableListOf<LlmRequest>()
            val runner =
                createRunner(config, workspace, llmCalls, sessionProvider = { _, model ->
                    requestedModel = model
                    testSession.copy(model = model)
                })

            runner.executeHeartbeat()

            assertEquals("custom/model", requestedModel)
        }

    @Test
    fun `executeHeartbeat delivers message when heartbeat_deliver called`() =
        runBlocking {
            Files.writeString(workspace.resolve("HEARTBEAT.md"), "Check alerts")
            val config =
                buildConfig(
                    HeartbeatConfig(interval = "PT1H", injectInto = "chat123", channel = "telegram"),
                )
            val pushed = mutableListOf<OutboundSocketMessage>()
            val toolExecutor = HeartbeatAwareToolExecutor()
            val runner =
                createRunner(
                    config,
                    workspace,
                    llmResponses =
                        listOf(
                            response(
                                toolCalls =
                                    listOf(
                                        ToolCall(
                                            id = "tc1",
                                            name = "heartbeat_deliver",
                                            arguments = """{"message":"Alert: high CPU"}""",
                                        ),
                                    ),
                            ),
                            response(content = "Done"),
                        ),
                    toolExecutor = toolExecutor,
                    pushCapture = pushed,
                )

            runner.executeHeartbeat()

            assertEquals(1, pushed.size)
            assertEquals("chat123", pushed[0].chatId)
            assertEquals("telegram", pushed[0].channel)
            assertEquals("Alert: high CPU", pushed[0].content)
        }

    @Test
    fun `executeHeartbeat does not deliver when LLM does not call heartbeat_deliver`() =
        runBlocking {
            Files.writeString(workspace.resolve("HEARTBEAT.md"), "Check alerts")
            val config =
                buildConfig(
                    HeartbeatConfig(interval = "PT1H", injectInto = "chat123", channel = "telegram"),
                )
            val pushed = mutableListOf<OutboundSocketMessage>()
            val runner = createRunner(config, workspace, pushCapture = pushed)

            runner.executeHeartbeat()

            assertTrue(pushed.isEmpty())
        }

    @Test
    fun `executeHeartbeat skips entirely when delivery target not configured`() =
        runBlocking {
            Files.writeString(workspace.resolve("HEARTBEAT.md"), "Check alerts")
            val config = buildConfig(HeartbeatConfig(interval = "PT1H"))
            val llmCalls = mutableListOf<LlmRequest>()
            val runner = createRunner(config, workspace, llmCalls)

            runner.executeHeartbeat()

            assertTrue(llmCalls.isEmpty())
        }

    @Test
    fun `concurrent run is skipped`() =
        runBlocking {
            Files.writeString(workspace.resolve("HEARTBEAT.md"), "Check something")
            val config = buildConfig(defaultHeartbeat)
            val llmCalls = mutableListOf<LlmRequest>()
            val runner = createRunner(config, workspace, llmCalls)

            // Simulate locking
            runner.acquireRunLock()
            runner.executeHeartbeat()
            runner.releaseRunLock()

            assertTrue(llmCalls.isEmpty())
        }

    @Test
    fun `tool calls include ChatContext with delivery chatId and channel`() =
        runBlocking {
            Files.writeString(workspace.resolve("HEARTBEAT.md"), "Check alerts")
            val config =
                buildConfig(
                    HeartbeatConfig(interval = "PT1H", injectInto = "telegram_123", channel = "telegram"),
                )
            var capturedChatContext: ChatContext? = null
            val chatContextCapturingExecutor =
                object : ToolExecutor {
                    override suspend fun executeAll(toolCalls: List<ToolCall>): List<ToolResult> {
                        capturedChatContext = kotlin.coroutines.coroutineContext[ChatContext]
                        return toolCalls.map { ToolResult(callId = it.id, content = "ok") }
                    }
                }
            val runner =
                createRunner(
                    config,
                    workspace,
                    llmResponses =
                        listOf(
                            response(
                                toolCalls =
                                    listOf(
                                        ToolCall(
                                            id = "tc1",
                                            name = "some_tool",
                                            arguments = "{}",
                                        ),
                                    ),
                            ),
                            response(content = "Done"),
                        ),
                    toolExecutor = chatContextCapturingExecutor,
                )

            runner.executeHeartbeat()

            assertNotNull(capturedChatContext, "ChatContext should be present in tool call coroutine context")
            assertEquals("telegram_123", capturedChatContext!!.chatId)
            assertEquals("telegram", capturedChatContext!!.channel)
        }

    // --- Helpers ---

    @Suppress("LongParameterList")
    private fun createRunner(
        config: EngineConfig,
        workspace: Path,
        llmCalls: MutableList<LlmRequest> = mutableListOf(),
        llmResponses: List<LlmResponse>? = null,
        toolExecutor: ToolExecutor? = null,
        sessionProvider: (suspend (String, String) -> Session)? = null,
        pushCapture: MutableList<OutboundSocketMessage> = mutableListOf(),
        persistence: HeartbeatPersistence = noOpPersistence(pushCapture),
    ): HeartbeatRunner {
        val responseIterator = (llmResponses ?: listOf(response(content = "Nothing to report"))).iterator()
        val chatFn: suspend (LlmRequest, String) -> LlmResponse = { request, _ ->
            llmCalls.add(request)
            if (responseIterator.hasNext()) responseIterator.next() else response(content = "Done")
        }
        val executor =
            toolExecutor ?: object : ToolExecutor {
                override suspend fun executeAll(toolCalls: List<ToolCall>): List<ToolResult> =
                    toolCalls.map { ToolResult(callId = it.id, content = "ok") }
            }
        val provider = sessionProvider ?: { _, model -> testSession.copy(model = model) }
        val workspaceLoader =
            object : WorkspaceLoader {
                override suspend fun loadSystemPrompt(): String = "You are an AI assistant."

                override suspend fun loadMemorySummary(): String? = null
            }
        val toolRegistry =
            object : ToolRegistry {
                override suspend fun listTools(
                    includeSkillList: Boolean,
                    includeSkillLoad: Boolean,
                    includeHeartbeatDeliver: Boolean,
                    includeScheduleDeliver: Boolean,
                    includeSendMessage: Boolean,
                ): List<ToolDef> = emptyList()
            }

        return HeartbeatRunner(
            config = config,
            chat = chatFn,
            toolExecutor = executor,
            getOrCreateSession = provider,
            workspaceLoader = workspaceLoader,
            toolRegistry = toolRegistry,
            workspacePath = workspace,
            maxToolCallRounds = config.processing.maxToolCallRounds,
            persistence = persistence,
        )
    }

    private fun noOpPersistence(
        pushCapture: MutableList<OutboundSocketMessage> = mutableListOf(),
    ): HeartbeatPersistence =
        HeartbeatPersistence(
            jsonlWriter = HeartbeatJsonlWriter(conversationsDir),
            persistDelivered = { _, _, _ -> },
            pushToGateway = { msg -> pushCapture.add(msg) },
        )

    private fun response(
        content: String? = null,
        toolCalls: List<ToolCall>? = null,
    ) = LlmResponse(content = content, toolCalls = toolCalls, usage = null, finishReason = FinishReason.STOP)

    @Test
    fun `executeHeartbeat writes full dialog to jsonl`() =
        runBlocking {
            Files.writeString(workspace.resolve("HEARTBEAT.md"), "Check the weather forecast")
            val config = buildConfig(defaultHeartbeat)
            val runner = createRunner(config, workspace)

            runner.executeHeartbeat()

            val today = LocalDate.now().toString()
            val jsonlFile = conversationsDir.resolve("heartbeat").resolve("$today.jsonl")
            assertTrue(
                java.nio.file.Files
                    .exists(jsonlFile),
                "JSONL file should be created",
            )
            val lines =
                java.nio.file.Files
                    .readAllLines(jsonlFile)
                    .filter { it.isNotBlank() }
            assertTrue(lines.isNotEmpty(), "JSONL should contain at least one entry")
            val roles =
                lines.map {
                    Json
                        .parseToJsonElement(it)
                        .jsonObject["role"]
                        ?.jsonPrimitive
                        ?.content
                }
            assertTrue(roles.contains("user"), "Should contain user message (HEARTBEAT.md content)")
            assertTrue(roles.none { it == "system" }, "Should not contain system messages")
        }

    @Test
    fun `executeHeartbeat writes jsonl even when deliver not called`() =
        runBlocking {
            Files.writeString(workspace.resolve("HEARTBEAT.md"), "Check alerts")
            val config = buildConfig(defaultHeartbeat)
            val runner = createRunner(config, workspace)

            runner.executeHeartbeat()

            val today = LocalDate.now().toString()
            val jsonlFile = conversationsDir.resolve("heartbeat").resolve("$today.jsonl")
            assertTrue(
                java.nio.file.Files
                    .exists(jsonlFile),
                "JSONL file should be created even without delivery",
            )
        }

    @Test
    fun `executeHeartbeat persists delivered message`() =
        runBlocking {
            Files.writeString(workspace.resolve("HEARTBEAT.md"), "Check alerts")
            val config =
                buildConfig(HeartbeatConfig(interval = "PT1H", injectInto = "telegram_999", channel = "telegram"))
            val persistedCalls =
                mutableListOf<Triple<String, String, String>>() // channel, chatId, content
            val runner =
                createRunner(
                    config,
                    workspace,
                    llmResponses =
                        listOf(
                            response(
                                toolCalls =
                                    listOf(
                                        ToolCall(
                                            id = "tc1",
                                            name = "heartbeat_deliver",
                                            arguments = """{"message":"Alert: memory high"}""",
                                        ),
                                    ),
                            ),
                            response(content = "Done"),
                        ),
                    toolExecutor = HeartbeatAwareToolExecutor(),
                    persistence =
                        noOpPersistence().copy(
                            persistDelivered = { channel, chatId, content ->
                                persistedCalls.add(Triple(channel, chatId, content))
                            },
                        ),
                )

            runner.executeHeartbeat()

            assertEquals(1, persistedCalls.size)
            val (channel, chatId, content) = persistedCalls[0]
            assertEquals("telegram", channel)
            assertEquals("telegram_999", chatId)
            assertEquals("Alert: memory high", content)
        }

    @Test
    fun `executeHeartbeat does not persist when deliver not called`() =
        runBlocking {
            Files.writeString(workspace.resolve("HEARTBEAT.md"), "Check alerts")
            val config = buildConfig(defaultHeartbeat)
            val persistedCalls = mutableListOf<Triple<String, String, String>>()
            val runner =
                createRunner(
                    config,
                    workspace,
                    persistence =
                        noOpPersistence().copy(
                            persistDelivered = { channel, chatId, content ->
                                persistedCalls.add(Triple(channel, chatId, content))
                            },
                        ),
                )

            runner.executeHeartbeat()

            assertTrue(persistedCalls.isEmpty())
        }

    @Test
    fun `executeHeartbeat does not write jsonl when heartbeat_md missing`() =
        runBlocking {
            val config = buildConfig(defaultHeartbeat)
            val runner = createRunner(config, workspace)

            runner.executeHeartbeat()

            val today = LocalDate.now().toString()
            val jsonlFile = conversationsDir.resolve("heartbeat").resolve("$today.jsonl")
            assertFalse(
                java.nio.file.Files
                    .exists(jsonlFile),
                "JSONL should not be created when HEARTBEAT.md is missing",
            )
        }

    private class HeartbeatAwareToolExecutor : ToolExecutor {
        override suspend fun executeAll(toolCalls: List<ToolCall>): List<ToolResult> =
            toolCalls.map { call ->
                if (call.name == "heartbeat_deliver") {
                    val ctx =
                        kotlin.coroutines.coroutineContext[HeartbeatDeliverContext]
                            ?: error("No HeartbeatDeliverContext")
                    val args =
                        kotlinx.serialization.json.Json
                            .parseToJsonElement(call.arguments)
                    val message = args.jsonObject["message"]?.jsonPrimitive?.content ?: ""
                    ctx.sink.deliver(message)
                    ToolResult(callId = call.id, content = "Message queued for delivery")
                } else {
                    ToolResult(callId = call.id, content = "ok")
                }
            }
    }
}
