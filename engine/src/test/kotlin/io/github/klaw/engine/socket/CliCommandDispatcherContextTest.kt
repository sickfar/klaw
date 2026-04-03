package io.github.klaw.engine.socket

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.ContextDiagnosticsBreakdown
import io.github.klaw.engine.context.ContextResult
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.init.InitCliHandler
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.llm.LlmUsageTracker
import io.github.klaw.engine.maintenance.ReindexService
import io.github.klaw.engine.memory.DailyConsolidationService
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.tools.DoctorDeepProbe
import io.github.klaw.engine.tools.EngineHealthProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class CliCommandDispatcherContextTest {
    private val initCliHandler = mockk<InitCliHandler>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val klawScheduler = mockk<KlawScheduler>(relaxed = true)
    private val memoryService = mockk<MemoryService>(relaxed = true)
    private val reindexService = mockk<ReindexService>(relaxed = true)
    private val skillRegistry = mockk<SkillRegistry>(relaxed = true)
    private val consolidationService = mockk<DailyConsolidationService>(relaxed = true)
    private val engineHealthProvider = mockk<EngineHealthProvider>(relaxed = true)
    private val llmUsageTracker = mockk<LlmUsageTracker>(relaxed = true)
    private val llmRouter = mockk<LlmRouter>(relaxed = true)
    private val engineConfig = mockk<EngineConfig>(relaxed = true)
    private val doctorDeepProbe = mockk<DoctorDeepProbe>(relaxed = true)
    private val commandsCliHandler = mockk<CommandsCliHandler>(relaxed = true)
    private val contextBuilder = mockk<ContextBuilder>(relaxed = true)
    private val contextDiagnoseHandler = ContextDiagnoseHandler(sessionManager, contextBuilder)

    private fun createDispatcher() =
        CliCommandDispatcher(
            initCliHandler,
            sessionManager,
            klawScheduler,
            memoryService,
            reindexService,
            skillRegistry,
            consolidationService,
            engineHealthProvider,
            llmUsageTracker,
            llmRouter,
            engineConfig,
            doctorDeepProbe,
            commandsCliHandler,
            contextDiagnoseHandler,
            io.github.klaw.engine.agent.AgentRegistry(),
        )

    private fun testSession(
        chatId: String = "telegram_292077641",
        model: String = "zai/glm-5",
        segmentStart: String = "2026-03-31T08:00:00Z",
    ) = Session(
        chatId = chatId,
        model = model,
        segmentStart = segmentStart,
        createdAt = Clock.System.now(),
        updatedAt = Clock.System.now(),
    )

    private fun testDiagnostics() =
        ContextDiagnosticsBreakdown(
            systemPromptTokens = 3200,
            systemPromptChars = 12800,
            summaryTokens = 1500,
            summaryChars = 6000,
            pendingTokens = 5,
            pendingChars = 25,
            toolTokens = 4200,
            toolChars = 16800,
            toolCount = 18,
            overhead = 8905,
            messageBudget = 91095,
            windowMessageCount = 42,
            windowMessageTokens = 18500L,
            windowMessageChars = 74000L,
            firstMessageTime = "2026-03-30T14:05:00Z",
            lastMessageTime = "2026-03-31T10:30:00Z",
            summaryCount = 3,
            hasEvictedSummaries = true,
            coverageEnd = "2026-03-30T18:00:00Z",
            autoRagEnabled = true,
            autoRagTriggered = true,
            autoRagResultCount = 3,
            compactionEnabled = true,
            compactionThreshold = 100000,
            compactionWouldTrigger = false,
            skillCount = 4,
            inlineSkills = true,
            toolNames = listOf("memory_search", "memory_save", "file_read"),
            windowTokenCharRatio = 4.0,
        )

    private fun stubContextBuilder(
        session: Session,
        diagnostics: ContextDiagnosticsBreakdown = testDiagnostics(),
    ) {
        val result =
            ContextResult(
                messages = emptyList(),
                tools = emptyList<ToolDef>(),
                diagnostics = diagnostics,
            )
        coEvery {
            contextBuilder.buildContext(
                session = session,
                pendingMessages = listOf("(diagnostic simulation)"),
                isSubagent = false,
                includeDiagnostics = true,
            )
        } returns result
    }

    @Test
    fun `context_diagnose routes to handler and returns text by default`() =
        runTest {
            val session = testSession()
            coEvery { sessionManager.getSession("telegram_292077641") } returns session
            stubContextBuilder(session)

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage("context_diagnose", mapOf("chat_id" to "telegram_292077641")),
                )

            assertTrue(result.contains("Context Diagnostics"), "Should contain header")
            assertTrue(result.contains("telegram_292077641"), "Should contain chatId")
            assertTrue(result.contains("zai/glm-5"), "Should contain model")
            assertTrue(result.contains("Budget Breakdown"), "Should contain budget section")
            assertTrue(result.contains("Message Window"), "Should contain window section")
        }

    @Test
    fun `context_diagnose with json param returns JSON`() =
        runTest {
            val session = testSession()
            coEvery { sessionManager.getSession("telegram_292077641") } returns session
            stubContextBuilder(session)

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage(
                        "context_diagnose",
                        mapOf("chat_id" to "telegram_292077641", "json" to "true"),
                    ),
                )

            val json = Json.parseToJsonElement(result).jsonObject
            assertEquals("telegram_292077641", json["chatId"]?.jsonPrimitive?.content)
            assertEquals("zai/glm-5", json["model"]?.jsonPrimitive?.content)
            assertEquals(3200, json["systemPromptTokens"]?.jsonPrimitive?.int)
            assertEquals(1500, json["summaryTokens"]?.jsonPrimitive?.int)
            assertEquals(4200, json["toolTokens"]?.jsonPrimitive?.int)
            assertEquals(18, json["toolCount"]?.jsonPrimitive?.int)
            assertEquals(42, json["windowMessageCount"]?.jsonPrimitive?.int)
            assertEquals(18500L, json["windowMessageTokens"]?.jsonPrimitive?.long)
            assertTrue(json["hasEvictedSummaries"]?.jsonPrimitive?.boolean == true)
            assertTrue(json["autoRagEnabled"]?.jsonPrimitive?.boolean == true)
            assertTrue(json["autoRagTriggered"]?.jsonPrimitive?.boolean == true)
            assertEquals(3, json["autoRagResultCount"]?.jsonPrimitive?.int)
            assertFalse(json["compactionWouldTrigger"]?.jsonPrimitive?.boolean == true)
            assertNotNull(json["toolNames"])
        }

    @Test
    fun `context_diagnose with unknown chat_id returns error`() =
        runTest {
            coEvery { sessionManager.getSession("unknown_chat") } returns null

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage("context_diagnose", mapOf("chat_id" to "unknown_chat")),
                )

            val json = Json.parseToJsonElement(result).jsonObject
            assertTrue(json["error"]?.jsonPrimitive?.content?.contains("session not found") == true)
            assertTrue(json["error"]?.jsonPrimitive?.content?.contains("unknown_chat") == true)
        }

    @Test
    fun `context_diagnose without chat_id uses most recent session`() =
        runTest {
            val session = testSession("recent_chat", "deepseek/chat")
            coEvery { sessionManager.getMostRecentSession() } returns session
            stubContextBuilder(session)

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("context_diagnose"))

            assertTrue(result.contains("recent_chat"), "Should use most recent session chatId")
            coVerify { sessionManager.getMostRecentSession() }
        }

    @Test
    fun `context_diagnose with no sessions returns error`() =
        runTest {
            coEvery { sessionManager.getMostRecentSession() } returns null

            val dispatcher = createDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage("context_diagnose"))

            val json = Json.parseToJsonElement(result).jsonObject
            assertTrue(json["error"]?.jsonPrimitive?.content?.contains("no active sessions") == true)
        }

    @Test
    fun `context_diagnose text format contains all major sections`() =
        runTest {
            val session = testSession()
            coEvery { sessionManager.getSession("telegram_292077641") } returns session
            stubContextBuilder(session)

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage("context_diagnose", mapOf("chat_id" to "telegram_292077641")),
                )

            // Unescape newlines so we can check individual lines
            val unescaped = result.replace("\\n", "\n")
            assertTrue(unescaped.contains("Session"), "Should have Session section")
            assertTrue(unescaped.contains("Budget Breakdown"), "Should have Budget section")
            assertTrue(unescaped.contains("Message Window"), "Should have Message Window section")
            assertTrue(unescaped.contains("Summaries"), "Should have Summaries section")
            assertTrue(unescaped.contains("Auto-RAG"), "Should have Auto-RAG section")
            assertTrue(unescaped.contains("Tools"), "Should have Tools section")
            assertTrue(unescaped.contains("Skills"), "Should have Skills section")
            assertTrue(unescaped.contains("Compaction"), "Should have Compaction section")
        }

    @Test
    fun `context_diagnose JSON includes session segmentStart`() =
        runTest {
            val session = testSession(segmentStart = "2026-03-31T08:00:00Z")
            coEvery { sessionManager.getSession("telegram_292077641") } returns session
            stubContextBuilder(session)

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage(
                        "context_diagnose",
                        mapOf("chat_id" to "telegram_292077641", "json" to "true"),
                    ),
                )

            val json = Json.parseToJsonElement(result).jsonObject
            assertEquals("2026-03-31T08:00:00Z", json["segmentStart"]?.jsonPrimitive?.content)
        }

    @Test
    fun `context_diagnose JSON toolNames is an array`() =
        runTest {
            val session = testSession()
            coEvery { sessionManager.getSession("telegram_292077641") } returns session
            stubContextBuilder(session)

            val dispatcher = createDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage(
                        "context_diagnose",
                        mapOf("chat_id" to "telegram_292077641", "json" to "true"),
                    ),
                )

            val json = Json.parseToJsonElement(result).jsonObject
            val toolNames = json["toolNames"]?.jsonArray
            assertNotNull(toolNames, "toolNames should be an array")
            assertTrue(toolNames!!.isNotEmpty())
            assertEquals("memory_search", toolNames[0].jsonPrimitive.content)
        }
}
