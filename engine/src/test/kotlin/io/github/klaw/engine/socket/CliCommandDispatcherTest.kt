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
import io.github.klaw.engine.session.SessionManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

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
}
