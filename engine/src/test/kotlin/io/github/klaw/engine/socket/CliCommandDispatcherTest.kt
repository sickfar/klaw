package io.github.klaw.engine.socket

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.engine.agent.AgentContext
import io.github.klaw.engine.agent.AgentRegistry
import io.github.klaw.engine.agent.AgentServices
import io.github.klaw.engine.context.FileSkillRegistry
import io.github.klaw.engine.context.SkillDetail
import io.github.klaw.engine.context.SkillValidationEntry
import io.github.klaw.engine.context.SkillValidationReport
import io.github.klaw.engine.init.InitCliHandler
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.llm.LlmUsageTracker
import io.github.klaw.engine.maintenance.ReindexService
import io.github.klaw.engine.memory.DailyConsolidationService
import io.github.klaw.engine.memory.MemoryCategoryInfo
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.tools.DoctorDeepProbe
import io.github.klaw.engine.tools.EngineHealthProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class CliCommandDispatcherTest {
    private val initCliHandler = mockk<InitCliHandler>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val klawScheduler = mockk<KlawScheduler>(relaxed = true)
    private val memoryService = mockk<MemoryService>(relaxed = true)
    private val reindexService = mockk<ReindexService>(relaxed = true)
    private val skillRegistry = mockk<FileSkillRegistry>(relaxed = true)
    private val consolidationService = mockk<DailyConsolidationService>(relaxed = true)
    private val engineHealthProvider = mockk<EngineHealthProvider>(relaxed = true)
    private val llmUsageTracker = mockk<LlmUsageTracker>(relaxed = true)
    private val llmRouter = mockk<LlmRouter>(relaxed = true)
    private val engineConfig = mockk<EngineConfig>(relaxed = true)
    private val doctorDeepProbe = mockk<DoctorDeepProbe>(relaxed = true)
    private val commandsCliHandler = mockk<CommandsCliHandler>(relaxed = true)

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
            llmRouter,
            engineConfig,
            doctorDeepProbe,
            commandsCliHandler,
            ContextDiagnoseHandler(mockk(relaxed = true), mockk(relaxed = true)),
            agentRegistry,
        )
    }

    @Test
    fun `skills_validate returns JSON report`() =
        runTest {
            val report =
                SkillValidationReport(
                    listOf(
                        SkillValidationEntry("valid-sk", "valid-sk", "workspace", true),
                        SkillValidationEntry(null, "broken-sk", "data", false, "missing SKILL.md"),
                    ),
                )
            coEvery { skillRegistry.validate() } returns report

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("skills_validate"))

            val json = Json.parseToJsonElement(result).jsonObject
            assertEquals(2, json["total"]?.jsonPrimitive?.int)
            assertEquals(1, json["valid"]?.jsonPrimitive?.int)
            assertEquals(1, json["errors"]?.jsonPrimitive?.int)

            val skills = json["skills"]?.jsonArray
            assertEquals(2, skills?.size)

            val validEntry =
                skills?.first { it.jsonObject["directory"]?.jsonPrimitive?.content == "valid-sk" }?.jsonObject
            assertTrue(validEntry?.get("valid")?.jsonPrimitive?.content == "true")
            assertEquals("valid-sk", validEntry?.get("name")?.jsonPrimitive?.content)

            val brokenEntry =
                skills?.first { it.jsonObject["directory"]?.jsonPrimitive?.content == "broken-sk" }?.jsonObject
            assertFalse(brokenEntry?.get("valid")?.jsonPrimitive?.content == "true")
            assertEquals("missing SKILL.md", brokenEntry?.get("error")?.jsonPrimitive?.content)
        }

    @Test
    fun `skills_list returns JSON with skills array`() =
        runTest {
            val details =
                listOf(
                    SkillDetail("alpha", "Alpha skill", "workspace"),
                    SkillDetail("beta", "Beta skill", "bundled"),
                )
            coEvery { skillRegistry.listDetailed() } returns details

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("skills_list"))

            val json = Json.parseToJsonElement(result).jsonObject
            assertEquals(2, json["total"]?.jsonPrimitive?.int)

            val skills = json["skills"]?.jsonArray
            assertEquals(2, skills?.size)

            val alpha = skills?.first { it.jsonObject["name"]?.jsonPrimitive?.content == "alpha" }?.jsonObject
            assertEquals("Alpha skill", alpha?.get("description")?.jsonPrimitive?.content)
            assertEquals("workspace", alpha?.get("source")?.jsonPrimitive?.content)
        }

    private fun testSession(
        chatId: String = "chat-1",
        model: String = "glm-5",
    ) = Session(
        chatId = chatId,
        model = model,
        segmentStart = Clock.System.now().toString(),
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    @Test
    fun `sessions_list returns JSON with chatId model createdAt updatedAt`() =
        runTest {
            val sessions = listOf(testSession("chat-a", "glm-5"), testSession("chat-b", "deepseek"))
            coEvery { sessionManager.listSessions() } returns sessions

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("sessions_list", mapOf("json" to "true")))

            val arr = Json.parseToJsonElement(result).jsonArray
            assertEquals(2, arr.size)

            val first = arr[0].jsonObject
            assertEquals("chat-a", first["chatId"]?.jsonPrimitive?.content)
            assertEquals("glm-5", first["model"]?.jsonPrimitive?.content)
            assertTrue("createdAt" in first, "Should have createdAt")
            assertTrue("updatedAt" in first, "Should have updatedAt")
        }

    @Test
    fun `sessions_list with active_minutes param calls listActiveSessions`() =
        runTest {
            val sessions = listOf(testSession())
            coEvery { sessionManager.listActiveSessions(any()) } returns sessions

            val dispatcher = createDispatcher()
            dispatcher.dispatch(
                CliRequestMessage("sessions_list", mapOf("active_minutes" to "30", "json" to "true")),
            )

            coVerify { sessionManager.listActiveSessions(any()) }
        }

    @Test
    fun `sessions_list with verbose param includes totalTokens`() =
        runTest {
            val sessions = listOf(testSession("chat-verbose"))
            coEvery { sessionManager.listSessions() } returns sessions
            coEvery { sessionManager.getTokenCount("chat-verbose") } returns 42L

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage("sessions_list", mapOf("verbose" to "true", "json" to "true")),
                )

            val arr = Json.parseToJsonElement(result).jsonArray
            val session = arr[0].jsonObject
            assertEquals(42L, session["totalTokens"]?.jsonPrimitive?.long)
        }

    @Test
    fun `sessions_list without json returns human-readable text`() =
        runTest {
            val sessions = listOf(testSession("chat-human"))
            coEvery { sessionManager.listSessions() } returns sessions

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("sessions_list"))

            assertFalse(result.startsWith("["), "Should not be JSON array")
            assertTrue(result.contains("chat-human"), "Should contain chatId")
        }

    @Test
    fun `sessions_cleanup calls cleanupSessions with default threshold`() =
        runTest {
            coEvery { sessionManager.cleanupSessions(any()) } returns 0

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("sessions_cleanup"))

            val json = Json.parseToJsonElement(result).jsonObject
            assertEquals(0, json["deleted"]?.jsonPrimitive?.int)
            coVerify { sessionManager.cleanupSessions(any()) }
        }

    @Test
    fun `sessions_cleanup with older_than_minutes uses custom threshold`() =
        runTest {
            coEvery { sessionManager.cleanupSessions(any()) } returns 5

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage("sessions_cleanup", mapOf("older_than_minutes" to "60")),
                )

            val json = Json.parseToJsonElement(result).jsonObject
            assertEquals(5, json["deleted"]?.jsonPrimitive?.int)
        }

    @Test
    fun `sessions command backward compat returns simple format`() =
        runTest {
            val sessions = listOf(testSession("chat-compat", "qwen"))
            coEvery { sessionManager.listSessions() } returns sessions

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("sessions"))

            val arr = Json.parseToJsonElement(result).jsonArray
            val session = arr[0].jsonObject
            assertEquals("chat-compat", session["chatId"]?.jsonPrimitive?.content)
            assertEquals("qwen", session["model"]?.jsonPrimitive?.content)
            // Old format should NOT have createdAt/updatedAt
            assertFalse("createdAt" in session, "Old format should not have createdAt")
        }

    // ── schedule_edit ──

    @Test
    fun `schedule_edit delegates to klawScheduler`() =
        runTest {
            coEvery { klawScheduler.edit("my-job", "0 0 9 * * ?", "new msg", "glm-5") } returns "OK: edited"

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage(
                        "schedule_edit",
                        mapOf("name" to "my-job", "cron" to "0 0 9 * * ?", "message" to "new msg", "model" to "glm-5"),
                    ),
                )

            assertEquals("OK: edited", result)
            coVerify { klawScheduler.edit("my-job", "0 0 9 * * ?", "new msg", "glm-5") }
        }

    @Test
    fun `schedule_edit with missing name returns error`() =
        runTest {
            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("schedule_edit", mapOf("cron" to "0 0 9 * * ?")))
            assertTrue(result.contains("error", ignoreCase = true), "Expected error, got: $result")
        }

    // ── schedule_enable ──

    @Test
    fun `schedule_enable delegates to klawScheduler`() =
        runTest {
            coEvery { klawScheduler.enable("my-job") } returns "OK: 'my-job' enabled"

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("schedule_enable", mapOf("name" to "my-job")))

            assertEquals("OK: 'my-job' enabled", result)
            coVerify { klawScheduler.enable("my-job") }
        }

    @Test
    fun `schedule_enable with missing name returns error`() =
        runTest {
            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("schedule_enable"))
            assertTrue(result.contains("error", ignoreCase = true), "Expected error, got: $result")
        }

    // ── schedule_disable ──

    @Test
    fun `schedule_disable delegates to klawScheduler`() =
        runTest {
            coEvery { klawScheduler.disable("my-job") } returns "OK: 'my-job' disabled"

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("schedule_disable", mapOf("name" to "my-job")))

            assertEquals("OK: 'my-job' disabled", result)
            coVerify { klawScheduler.disable("my-job") }
        }

    @Test
    fun `schedule_disable with missing name returns error`() =
        runTest {
            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("schedule_disable"))
            assertTrue(result.contains("error", ignoreCase = true), "Expected error, got: $result")
        }

    // ── schedule_run ──

    @Test
    fun `schedule_run delegates to klawScheduler`() =
        runTest {
            coEvery { klawScheduler.run("my-job") } returns "OK: 'my-job' triggered"

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("schedule_run", mapOf("name" to "my-job")))

            assertEquals("OK: 'my-job' triggered", result)
            coVerify { klawScheduler.run("my-job") }
        }

    @Test
    fun `schedule_run with missing name returns error`() =
        runTest {
            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("schedule_run"))
            assertTrue(result.contains("error", ignoreCase = true), "Expected error, got: $result")
        }

    // ── schedule_runs ──

    @Test
    fun `schedule_runs delegates to klawScheduler`() =
        runTest {
            val runsJson =
                """[{"name":"my-job","status":"COMPLETED","startTime":"2026-03-22T10:00:00Z"}]"""
            coEvery { klawScheduler.runs("my-job", 20) } returns runsJson

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("schedule_runs", mapOf("name" to "my-job")))

            val arr = Json.parseToJsonElement(result).jsonArray
            assertEquals(1, arr.size)
            assertEquals("my-job", arr[0].jsonObject["name"]?.jsonPrimitive?.content)
            assertEquals("COMPLETED", arr[0].jsonObject["status"]?.jsonPrimitive?.content)
            coVerify { klawScheduler.runs("my-job", 20) }
        }

    @Test
    fun `schedule_runs with missing name returns error`() =
        runTest {
            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("schedule_runs"))
            assertTrue(result.contains("error", ignoreCase = true), "Expected error, got: $result")
        }

    @Test
    fun `schedule_runs with custom limit`() =
        runTest {
            coEvery { klawScheduler.runs("my-job", 5) } returns "[]"

            val dispatcher = createDispatcher()
            dispatcher.dispatch(CliRequestMessage("schedule_runs", mapOf("name" to "my-job", "limit" to "5")))

            coVerify { klawScheduler.runs("my-job", 5) }
        }

    // ── memory_categories_list ──

    @Test
    fun `memory_categories_list returns plain text by default`() =
        runTest {
            val categories =
                listOf(
                    MemoryCategoryInfo(1, "daily-summary", 15, 42),
                    MemoryCategoryInfo(2, "project-notes", 3, 10),
                )
            coEvery { memoryService.getTopCategories(any()) } returns categories

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("memory_categories_list"))

            assertTrue(result.contains("daily-summary"))
            assertTrue(result.contains("42 entries"))
            assertFalse(result.startsWith("{"))
        }

    @Test
    fun `memory_categories_list with json param returns JSON array`() =
        runTest {
            val categories =
                listOf(
                    MemoryCategoryInfo(1, "daily-summary", 15, 42),
                    MemoryCategoryInfo(2, "project-notes", 3, 10),
                )
            coEvery { memoryService.getTopCategories(any()) } returns categories
            coEvery { memoryService.getTotalCategoryCount() } returns 2L

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage("memory_categories_list", mapOf("json" to "true")),
                )

            val json = Json.parseToJsonElement(result).jsonObject
            assertEquals(2, json["total"]?.jsonPrimitive?.int)

            val cats = json["categories"]?.jsonArray
            assertEquals(2, cats?.size)

            val first = cats?.first()?.jsonObject
            assertEquals("daily-summary", first?.get("name")?.jsonPrimitive?.content)
            assertEquals(42, first?.get("entryCount")?.jsonPrimitive?.long)
            assertEquals(15, first?.get("accessCount")?.jsonPrimitive?.long)
            assertEquals(1, first?.get("id")?.jsonPrimitive?.long)
        }

    @Test
    fun `memory_categories_list json with empty categories`() =
        runTest {
            coEvery { memoryService.getTopCategories(any()) } returns emptyList()
            coEvery { memoryService.getTotalCategoryCount() } returns 0L

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage("memory_categories_list", mapOf("json" to "true")),
                )

            val json = Json.parseToJsonElement(result).jsonObject
            assertEquals(0, json["total"]?.jsonPrimitive?.int)
            assertTrue(json["categories"]?.jsonArray?.isEmpty() == true)
        }

    // ── schedule_status ──

    @Test
    fun `schedule_status returns scheduler status`() =
        runTest {
            coEvery {
                klawScheduler.status()
            } returns """{"started":true,"standby":false,"jobCount":3,"executingNow":0}"""

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("schedule_status"))

            val json = Json.parseToJsonElement(result).jsonObject
            assertTrue(json["started"]?.jsonPrimitive?.content == "true")
            assertEquals(3, json["jobCount"]?.jsonPrimitive?.int)
        }

    // ── models_list ──

    @Test
    fun `models_list returns JSON with configured model names`() =
        runTest {
            coEvery { engineConfig.models } returns
                mapOf(
                    "test/model" to ModelConfig(),
                    "deepseek/chat" to ModelConfig(),
                )

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("models_list"))

            val json = Json.parseToJsonElement(result).jsonObject
            val models = json["models"]?.jsonArray
            assertEquals(2, models?.size)
            val names = models?.map { it.jsonPrimitive.content }
            assertTrue(names?.contains("test/model") == true)
            assertTrue(names?.contains("deepseek/chat") == true)
        }

    @Test
    fun `models_list returns empty array when no models configured`() =
        runTest {
            coEvery { engineConfig.models } returns emptyMap()

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("models_list"))

            val json = Json.parseToJsonElement(result).jsonObject
            assertTrue(json["models"]?.jsonArray?.isEmpty() == true)
        }

    // ── memory_facts_list ──

    @Test
    fun `memory_facts_list returns facts for existing category`() =
        runTest {
            val factsJson =
                """[{"id":1,"category":"test-cat","content":"fact one","createdAt":"2026-01-01","updatedAt":"2026-01-01"}]"""
            coEvery { memoryService.listFactsByCategory("test-cat") } returns factsJson

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage("memory_facts_list", mapOf("category" to "test-cat")),
                )

            val arr = Json.parseToJsonElement(result).jsonArray
            assertEquals(1, arr.size)
            assertEquals("fact one", arr[0].jsonObject["content"]?.jsonPrimitive?.content)
        }

    @Test
    fun `memory_facts_list returns error for missing category param`() =
        runTest {
            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("memory_facts_list"))

            assertTrue(result.contains("error", ignoreCase = true))
        }

    // ── session_messages ──

    @Test
    fun `session_messages returns messages JSON for existing chatId`() =
        runTest {
            val messages =
                listOf(
                    io.github.klaw.engine.db.Messages(
                        id = "m1",
                        channel = "console",
                        chat_id = "chat-1",
                        role = "user",
                        type = "text",
                        content = "hello",
                        metadata = null,
                        created_at = "2026-03-24T10:00:00Z",
                        tokens = 5,
                    ),
                )
            coEvery { sessionManager.getMessages("chat-1") } returns messages

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage("session_messages", mapOf("chat_id" to "chat-1")),
                )

            val arr = Json.parseToJsonElement(result).jsonArray
            assertEquals(1, arr.size)
            assertEquals("user", arr[0].jsonObject["role"]?.jsonPrimitive?.content)
            assertEquals("hello", arr[0].jsonObject["content"]?.jsonPrimitive?.content)
        }

    @Test
    fun `session_messages returns empty array for unknown chatId`() =
        runTest {
            coEvery { sessionManager.getMessages("unknown") } returns emptyList()

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage("session_messages", mapOf("chat_id" to "unknown")),
                )

            val arr = Json.parseToJsonElement(result).jsonArray
            assertTrue(arr.isEmpty())
        }

    @Test
    fun `session_messages returns error for missing chat_id param`() =
        runTest {
            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("session_messages"))

            assertTrue(result.contains("error", ignoreCase = true))
        }

    @Test
    fun `doctor_deep dispatches to DoctorDeepProbe`() =
        runTest {
            val probeResponse = """{"embedding":{"status":"ok"},"database":{"status":"ok"}}"""
            coEvery { doctorDeepProbe.probe() } returns probeResponse

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("doctor_deep"))

            assertEquals(probeResponse, result)
            coVerify { doctorDeepProbe.probe() }
        }

    @Test
    fun `commands_list returns JSON with all commands`() =
        runTest {
            val commandsJson =
                """{"commands":[{"name":"new","description":"Start new conversation"},{"name":"help","description":"Show help"}]}"""
            every { commandsCliHandler.handleCommandsList() } returns commandsJson

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("commands_list"))

            val json = Json.parseToJsonElement(result).jsonObject
            val cmds = json["commands"]?.jsonArray
            assertEquals(2, cmds?.size)

            val newCmd = cmds?.first { it.jsonObject["name"]?.jsonPrimitive?.content == "new" }?.jsonObject
            assertEquals("Start new conversation", newCmd?.get("description")?.jsonPrimitive?.content)

            val helpCmd = cmds?.first { it.jsonObject["name"]?.jsonPrimitive?.content == "help" }?.jsonObject
            assertEquals("Show help", helpCmd?.get("description")?.jsonPrimitive?.content)
        }
}
