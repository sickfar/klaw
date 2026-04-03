package io.github.klaw.engine.socket

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.engine.agent.AgentContext
import io.github.klaw.engine.agent.AgentRegistry
import io.github.klaw.engine.agent.AgentServices
import io.github.klaw.engine.context.FileSkillRegistry
import io.github.klaw.engine.init.InitCliHandler
import io.github.klaw.engine.llm.LlmUsageTracker
import io.github.klaw.engine.llm.ModelUsageSnapshot
import io.github.klaw.engine.maintenance.ReindexService
import io.github.klaw.engine.memory.DailyConsolidationService
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.tools.EngineHealth
import io.github.klaw.engine.tools.EngineHealthProvider
import io.github.klaw.engine.tools.SandboxHealth
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class CliCommandDispatcherStatusTest {
    private val initCliHandler = mockk<InitCliHandler>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val klawScheduler = mockk<KlawScheduler>(relaxed = true)
    private val memoryService = mockk<MemoryService>(relaxed = true)
    private val reindexService = mockk<ReindexService>(relaxed = true)
    private val skillRegistry = mockk<FileSkillRegistry>(relaxed = true)
    private val consolidationService = mockk<DailyConsolidationService>(relaxed = true)
    private val engineHealthProvider = mockk<EngineHealthProvider>(relaxed = true)
    private val llmUsageTracker = mockk<LlmUsageTracker>(relaxed = true)
    private val engineConfig = mockk<EngineConfig>(relaxed = true)
    private val commandsCliHandler = mockk<CommandsCliHandler>(relaxed = true)

    private val testHealth =
        EngineHealth(
            gatewayStatus = "connected",
            engineUptime = "PT2H30M",
            docker = false,
            sandbox = SandboxHealth(enabled = false, keepAlive = false, containerActive = false, executions = 0),
            mcpServers = listOf("brave-search"),
            embeddingService = "onnx",
            sqliteVec = true,
            databaseOk = true,
            scheduledJobs = 2,
            activeSessions = 3,
            pendingDeliveries = 0,
            heartbeatRunning = false,
            docsEnabled = true,
            memoryFacts = 42,
            runningSubagents = 0,
        )

    private fun createDispatcher(): CliCommandDispatcher {
        val agentRegistry = AgentRegistry()
        agentRegistry.register(
            "default",
            AgentContext(
                agentId = "default",
                agentConfig = io.github.klaw.common.config.AgentConfig(workspace = "/tmp/test"),
                services =
                    AgentServices(
                        sessionManager = sessionManager,
                        scheduler = klawScheduler,
                        memoryService = memoryService,
                        skillRegistry = skillRegistry,
                    ),
            ),
        )
        return CliCommandDispatcher(
            initCliHandler,
            reindexService,
            consolidationService,
            engineHealthProvider,
            llmUsageTracker,
            mockk(relaxed = true),
            engineConfig,
            mockk(relaxed = true),
            commandsCliHandler,
            ContextDiagnoseHandler(mockk(relaxed = true), mockk(relaxed = true)),
            agentRegistry,
        )
    }

    private fun stubSessions(count: Int = 2) {
        val sessions =
            (1..count).map { i ->
                Session(
                    chatId = "chat-$i",
                    model = "glm-5",
                    segmentStart = Clock.System.now().toString(),
                    createdAt = Clock.System.now(),
                    updatedAt = Clock.System.now(),
                )
            }
        coEvery { sessionManager.listSessions() } returns sessions
    }

    @Test
    fun `status no params returns basic JSON backward compat`() =
        runTest {
            stubSessions(3)

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("status"))
            val json = Json.parseToJsonElement(result).jsonObject

            assertEquals("ok", json["status"]?.jsonPrimitive?.content)
            assertEquals("klaw", json["engine"]?.jsonPrimitive?.content)
            assertEquals(3, json["sessions"]?.jsonPrimitive?.int)
            assertTrue("health" !in json, "Basic status should not contain 'health'")
            assertTrue("usage" !in json, "Basic status should not contain 'usage'")
        }

    @Test
    fun `status deep returns text with health labels`() =
        runTest {
            stubSessions()
            coEvery { engineHealthProvider.getHealth() } returns testHealth

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("status", mapOf("deep" to "true")))

            assertTrue(result.contains("Gateway:"), "Should contain 'Gateway:', got: $result")
            assertTrue(result.contains("connected"), "Should contain gateway status value")
            assertTrue(result.contains("Uptime:"), "Should contain 'Uptime:'")
            assertTrue(result.contains("Database:"), "Should contain 'Database:'")
            assertTrue(result.contains("Sessions:"), "Should contain 'Sessions:'")
        }

    @Test
    fun `status deep json returns EngineHealth JSON`() =
        runTest {
            stubSessions()
            coEvery { engineHealthProvider.getHealth() } returns testHealth

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(CliRequestMessage("status", mapOf("deep" to "true", "json" to "true")))
            val json = Json.parseToJsonElement(result).jsonObject

            assertTrue("health" in json, "Should contain 'health' key, got: $result")
            val health = json["health"]!!.jsonObject
            assertEquals("connected", health["gateway_status"]?.jsonPrimitive?.content)
            assertEquals("PT2H30M", health["engine_uptime"]?.jsonPrimitive?.content)
            assertTrue(health["database_ok"]?.jsonPrimitive?.boolean == true)
            assertEquals(3, health["active_sessions"]?.jsonPrimitive?.int)
            assertEquals(42, health["memory_facts"]?.jsonPrimitive?.int)
        }

    @Test
    fun `status usage returns text with no usage data`() =
        runTest {
            stubSessions()
            every { llmUsageTracker.snapshot() } returns emptyMap()

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("status", mapOf("usage" to "true")))

            assertTrue(
                result.contains("No usage data"),
                "Should contain 'No usage data' when empty, got: $result",
            )
        }

    @Test
    fun `status usage returns text with model stats`() =
        runTest {
            stubSessions()
            every { llmUsageTracker.snapshot() } returns
                mapOf(
                    "glm/glm-5" to ModelUsageSnapshot(10, 500, 300, 800),
                    "deepseek/deepseek-chat" to ModelUsageSnapshot(5, 200, 100, 300),
                )

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("status", mapOf("usage" to "true")))

            assertTrue(result.contains("LLM Usage"), "Should contain 'LLM Usage' header, got: $result")
            assertTrue(result.contains("glm/glm-5"), "Should contain model name")
            assertTrue(result.contains("10"), "Should contain request count")
            assertTrue(result.contains("deepseek/deepseek-chat"), "Should contain second model")
        }

    @Test
    fun `status usage json returns usage JSON`() =
        runTest {
            stubSessions()
            every { llmUsageTracker.snapshot() } returns
                mapOf("glm/glm-5" to ModelUsageSnapshot(10, 500, 300, 800))

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(CliRequestMessage("status", mapOf("usage" to "true", "json" to "true")))
            val json = Json.parseToJsonElement(result).jsonObject

            assertTrue("usage" in json, "Should contain 'usage' key, got: $result")
            val usage = json["usage"]!!.jsonObject
            val model = usage["glm/glm-5"]!!.jsonObject
            assertEquals(10L, model["request_count"]?.jsonPrimitive?.long)
            assertEquals(500L, model["prompt_tokens"]?.jsonPrimitive?.long)
            assertEquals(300L, model["completion_tokens"]?.jsonPrimitive?.long)
            assertEquals(800L, model["total_tokens"]?.jsonPrimitive?.long)
        }

    @Test
    fun `status all json combines health and usage`() =
        runTest {
            stubSessions()
            coEvery { engineHealthProvider.getHealth() } returns testHealth
            every { llmUsageTracker.snapshot() } returns
                mapOf("glm/glm-5" to ModelUsageSnapshot(5, 100, 50, 150))

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(CliRequestMessage("status", mapOf("all" to "true", "json" to "true")))
            val json = Json.parseToJsonElement(result).jsonObject

            assertTrue("status" in json, "Should contain 'status'")
            assertTrue("health" in json, "Should contain 'health'")
            assertTrue("usage" in json, "Should contain 'usage'")
        }

    @Test
    fun `status all text combines deep and usage labels`() =
        runTest {
            stubSessions()
            coEvery { engineHealthProvider.getHealth() } returns testHealth
            every { llmUsageTracker.snapshot() } returns
                mapOf("glm/glm-5" to ModelUsageSnapshot(5, 100, 50, 150))

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("status", mapOf("all" to "true")))

            assertTrue(result.contains("Gateway:"), "Should contain deep status labels")
            assertTrue(result.contains("LLM Usage"), "Should contain usage section")
        }
}
