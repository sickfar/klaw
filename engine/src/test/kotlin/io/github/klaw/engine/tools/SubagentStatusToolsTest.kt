package io.github.klaw.engine.tools

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.VirtualTableSetup
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SubagentStatusToolsTest {
    private lateinit var repo: SubagentRunRepository
    private lateinit var tools: SubagentStatusTools
    private val activeJobs = ActiveSubagentJobs()
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        val database = KlawDatabase(driver)
        repo = SubagentRunRepository(database)
        activeJobs.jobs.clear()
        tools = SubagentStatusTools(repo, activeJobs)
    }

    @Test
    fun `status returns JSON with all fields for COMPLETED run`() {
        repo.startRun(StartRunRequest("run-1", "my-task", "test/model", "inject-chat", "source-chat", "telegram"))
        repo.completeRun("run-1", lastResponse = "Done!", deliveredResult = "Result delivered")

        val result = tools.status("run-1", "source-chat")
        val obj = json.parseToJsonElement(result).jsonObject

        assertEquals("run-1", obj["id"]?.jsonPrimitive?.content)
        assertEquals("my-task", obj["name"]?.jsonPrimitive?.content)
        assertEquals("COMPLETED", obj["status"]?.jsonPrimitive?.content)
        assertEquals("test/model", obj["model"]?.jsonPrimitive?.content)
        assertNotNull(obj["start_time"])
        assertNotNull(obj["end_time"])
        assertNotNull(obj["duration_ms"])
        assertEquals("Done!", obj["last_response"]?.jsonPrimitive?.content)
        assertEquals("Result delivered", obj["result"]?.jsonPrimitive?.content)
    }

    @Test
    fun `status returns JSON with RUNNING status and no end fields`() {
        repo.startRun(StartRunRequest("run-2", "running-task", null, null, "source-chat", "telegram"))

        val result = tools.status("run-2", "source-chat")
        val obj = json.parseToJsonElement(result).jsonObject

        assertEquals("RUNNING", obj["status"]?.jsonPrimitive?.content)
        assertEquals("running-task", obj["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `status returns not found for nonexistent ID`() {
        val result = tools.status("nonexistent", "source-chat")
        assertTrue(result.contains("not found"), "Expected 'not found' in: $result")
    }

    @Test
    fun `status returns not found when ID belongs to different session`() {
        repo.startRun(StartRunRequest("run-scoped", "task", null, null, "session-A", "telegram"))

        val result = tools.status("run-scoped", "session-B")
        assertTrue(result.contains("not found"), "Session B should not see session A's run: $result")
    }

    @Test
    fun `list returns JSON array of recent runs for this session`() {
        repo.startRun(StartRunRequest("run-a", "task-a", null, null, "my-session", "telegram"))
        repo.startRun(StartRunRequest("run-b", "task-b", null, null, "my-session", "telegram"))
        repo.startRun(StartRunRequest("run-other", "task-other", null, null, "other-session", "telegram"))

        val result = tools.list("my-session")
        val array = json.parseToJsonElement(result).jsonArray

        assertEquals(2, array.size)
        assertTrue(
            array.all {
                it.jsonObject["name"]
                    ?.jsonPrimitive
                    ?.content
                    ?.startsWith("task-") == true
            },
        )
    }

    @Test
    fun `list returns empty array when no runs for this session`() {
        val result = tools.list("empty-session")
        val array = json.parseToJsonElement(result).jsonArray
        assertEquals(0, array.size)
    }

    @Test
    fun `cancel returns success for running subagent owned by session`() {
        repo.startRun(StartRunRequest("run-cancel", "cancel-task", null, null, "my-session", "telegram"))
        val job = Job()
        activeJobs.jobs["run-cancel"] = job

        val result = tools.cancel("run-cancel", "my-session")

        assertTrue(result.contains("cancelled"), "Expected 'cancelled' in: $result")
        assertTrue(job.isCancelled, "Job should be cancelled")

        val run = repo.getById("run-cancel", "my-session")
        assertEquals("CANCELLED", run?.status)
    }

    @Test
    fun `cancel returns not found for wrong session`() {
        repo.startRun(StartRunRequest("run-owned", "task", null, null, "session-A", "telegram"))
        activeJobs.jobs["run-owned"] = Job()

        val result = tools.cancel("run-owned", "session-B")
        assertTrue(result.contains("not found"), "Session B should not cancel session A's run: $result")
    }

    @Test
    fun `cancel returns error for already completed subagent`() {
        repo.startRun(StartRunRequest("run-done", "task", null, null, "my-session", "telegram"))
        repo.completeRun("run-done", "ok", null)

        val result = tools.cancel("run-done", "my-session")
        assertTrue(
            result.contains("not running") || result.contains("already"),
            "Should indicate run is not running: $result",
        )
    }

    @Test
    fun `cancel returns not found for nonexistent ID`() {
        val result = tools.cancel("nonexistent", "my-session")
        assertTrue(result.contains("not found"), "Expected 'not found' in: $result")
    }
}
