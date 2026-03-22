package io.github.klaw.engine.socket

import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.engine.context.SkillDetail
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.context.SkillValidationEntry
import io.github.klaw.engine.context.SkillValidationReport
import io.github.klaw.engine.init.InitCliHandler
import io.github.klaw.engine.maintenance.ReindexService
import io.github.klaw.engine.memory.DailyConsolidationService
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
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
import kotlin.time.Instant

class CliCommandDispatcherTest {
    private val initCliHandler = mockk<InitCliHandler>(relaxed = true)
    private val sessionManager = mockk<SessionManager>(relaxed = true)
    private val klawScheduler = mockk<KlawScheduler>(relaxed = true)
    private val memoryService = mockk<MemoryService>(relaxed = true)
    private val reindexService = mockk<ReindexService>(relaxed = true)
    private val skillRegistry = mockk<SkillRegistry>(relaxed = true)
    private val consolidationService = mockk<DailyConsolidationService>(relaxed = true)

    private fun createDispatcher() =
        CliCommandDispatcher(
            initCliHandler,
            sessionManager,
            klawScheduler,
            memoryService,
            reindexService,
            skillRegistry,
            consolidationService,
        )

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
}
