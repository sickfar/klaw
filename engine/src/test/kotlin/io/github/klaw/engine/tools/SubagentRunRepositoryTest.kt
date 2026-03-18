package io.github.klaw.engine.tools

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.VirtualTableSetup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SubagentRunRepositoryTest {
    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: KlawDatabase
    private lateinit var repo: SubagentRunRepository

    @BeforeEach
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)
        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = false)
        database = KlawDatabase(driver)
        repo = SubagentRunRepository(database)
    }

    @Test
    fun `startRun inserts RUNNING record with correct fields`() {
        repo.startRun(
            StartRunRequest(
                id = "run-1",
                name = "test-task",
                model = "test/model",
                injectInto = "chat-123",
                sourceChatId = "source-chat-1",
                sourceChannel = "telegram",
            ),
        )

        val run = repo.getById("run-1", "source-chat-1")
        assertNotNull(run)
        assertEquals("run-1", run!!.id)
        assertEquals("test-task", run.name)
        assertEquals("RUNNING", run.status)
        assertEquals("test/model", run.model)
        assertEquals("source-chat-1", run.sourceChatId)
        assertEquals("telegram", run.sourceChannel)
        assertNotNull(run.startTime)
        assertNull(run.endTime)
        assertNull(run.durationMs)
        assertNull(run.result)
        assertNull(run.lastResponse)
        assertNull(run.error)
        assertEquals("chat-123", run.injectInto)
    }

    @Test
    fun `startRun with null optional fields`() {
        repo.startRun(
            StartRunRequest(
                id = "run-2",
                name = "simple-task",
                model = null,
                injectInto = null,
                sourceChatId = "source-chat-1",
                sourceChannel = "telegram",
            ),
        )

        val run = repo.getById("run-2", "source-chat-1")
        assertNotNull(run)
        assertNull(run!!.model)
        assertNull(run.injectInto)
    }

    @Test
    fun `completeRun sets status, end_time, duration_ms, both result fields`() {
        repo.startRun(StartRunRequest("run-3", "task", "model", null, "chat-1", "telegram"))

        repo.completeRun("run-3", lastResponse = "LLM said hello", deliveredResult = "Delivered to user")

        val run = repo.getById("run-3", "chat-1")
        assertNotNull(run)
        assertEquals("COMPLETED", run!!.status)
        assertNotNull(run.endTime)
        assertNotNull(run.durationMs)
        assertTrue(run.durationMs!! >= 0)
        assertEquals("LLM said hello", run.lastResponse)
        assertEquals("Delivered to user", run.result)
        assertNull(run.error)
    }

    @Test
    fun `completeRun truncates result and lastResponse at MAX_RESULT_LENGTH`() {
        repo.startRun(StartRunRequest("run-trunc", "task", null, null, "chat-1", "telegram"))

        val longText = "A".repeat(3000)
        repo.completeRun("run-trunc", lastResponse = longText, deliveredResult = longText)

        val run = repo.getById("run-trunc", "chat-1")
        assertNotNull(run)
        assertEquals(SubagentRunRepository.MAX_RESULT_LENGTH, run!!.lastResponse!!.length)
        assertEquals(SubagentRunRepository.MAX_RESULT_LENGTH, run.result!!.length)
    }

    @Test
    fun `completeRun with null results`() {
        repo.startRun(StartRunRequest("run-null", "task", null, null, "chat-1", "telegram"))

        repo.completeRun("run-null", lastResponse = null, deliveredResult = null)

        val run = repo.getById("run-null", "chat-1")
        assertNotNull(run)
        assertEquals("COMPLETED", run!!.status)
        assertNull(run.lastResponse)
        assertNull(run.result)
    }

    @Test
    fun `failRun sets status to FAILED with structured error info`() {
        repo.startRun(StartRunRequest("run-fail", "task", null, null, "chat-1", "telegram"))

        repo.failRun("run-fail", "ProviderError(status=429)")

        val run = repo.getById("run-fail", "chat-1")
        assertNotNull(run)
        assertEquals("FAILED", run!!.status)
        assertNotNull(run.endTime)
        assertNotNull(run.durationMs)
        assertEquals("ProviderError(status=429)", run.error)
        assertNull(run.result)
        assertNull(run.lastResponse)
    }

    @Test
    fun `cancelRun sets status to CANCELLED`() {
        repo.startRun(StartRunRequest("run-cancel", "task", null, null, "chat-1", "telegram"))

        repo.cancelRun("run-cancel")

        val run = repo.getById("run-cancel", "chat-1")
        assertNotNull(run)
        assertEquals("CANCELLED", run!!.status)
        assertNotNull(run.endTime)
        assertNotNull(run.durationMs)
        assertNull(run.error)
    }

    @Test
    fun `getById returns null for nonexistent ID`() {
        val run = repo.getById("nonexistent", "chat-1")
        assertNull(run)
    }

    @Test
    fun `getById returns null if sourceChatId does not match - session scoping`() {
        repo.startRun(StartRunRequest("run-scoped", "task", null, null, "session-A", "telegram"))

        val fromA = repo.getById("run-scoped", "session-A")
        assertNotNull(fromA)

        val fromB = repo.getById("run-scoped", "session-B")
        assertNull(fromB, "Session B should not see session A's subagent")
    }

    @Test
    fun `listRecent returns only runs for the given sourceChatId`() {
        repo.startRun(StartRunRequest("run-a1", "task-a", null, null, "session-A", "telegram"))
        repo.startRun(StartRunRequest("run-b1", "task-b", null, null, "session-B", "telegram"))
        repo.startRun(StartRunRequest("run-a2", "task-a2", null, null, "session-A", "telegram"))

        val runsA = repo.listRecent("session-A")
        assertEquals(2, runsA.size)
        assertTrue(runsA.all { it.sourceChatId == "session-A" })

        val runsB = repo.listRecent("session-B")
        assertEquals(1, runsB.size)
        assertEquals("session-B", runsB[0].sourceChatId)
    }

    @Test
    fun `listRecent returns ordered by start_time DESC and respects limit`() {
        repeat(5) { i ->
            repo.startRun(StartRunRequest("run-$i", "task-$i", null, null, "chat-1", "telegram"))
        }

        val runs = repo.listRecent("chat-1", limit = 3)
        assertEquals(3, runs.size)
        // Should be most recent first
        assertEquals("run-4", runs[0].id)
        assertEquals("run-3", runs[1].id)
        assertEquals("run-2", runs[2].id)
    }

    @Test
    fun `countByStatus returns correct count globally`() {
        repo.startRun(StartRunRequest("run-r1", "task1", null, null, "chat-1", "telegram"))
        repo.startRun(StartRunRequest("run-r2", "task2", null, null, "chat-2", "telegram"))
        repo.startRun(StartRunRequest("run-c1", "task3", null, null, "chat-1", "telegram"))
        repo.completeRun("run-c1", "done", null)

        assertEquals(2, repo.countByStatus("RUNNING"))
        assertEquals(1, repo.countByStatus("COMPLETED"))
        assertEquals(0, repo.countByStatus("FAILED"))
    }

    @Test
    fun `markStaleRunsFailed updates all RUNNING records to FAILED with EngineCrash`() {
        repo.startRun(StartRunRequest("run-stale1", "task1", null, null, "chat-1", "telegram"))
        repo.startRun(StartRunRequest("run-stale2", "task2", null, null, "chat-2", "telegram"))
        repo.startRun(StartRunRequest("run-done", "task3", null, null, "chat-1", "telegram"))
        repo.completeRun("run-done", "ok", null)

        repo.markStaleRunsFailed()

        val stale1 = repo.getById("run-stale1", "chat-1")
        assertEquals("FAILED", stale1!!.status)
        assertEquals("EngineCrash", stale1.error)

        val stale2 = repo.getById("run-stale2", "chat-2")
        assertEquals("FAILED", stale2!!.status)
        assertEquals("EngineCrash", stale2.error)

        // Completed run should not be affected
        val done = repo.getById("run-done", "chat-1")
        assertEquals("COMPLETED", done!!.status)
        assertNull(done.error)
    }

    @Test
    fun `pruneOldRuns keeps only last N entries`() {
        repeat(10) { i ->
            repo.startRun(StartRunRequest("run-prune-$i", "task-$i", null, null, "chat-1", "telegram"))
        }

        repo.pruneOldRuns(keepCount = 5)

        // Only the 5 most recent should remain
        val allRuns = repo.listRecent("chat-1", limit = 100)
        assertEquals(5, allRuns.size)
        assertEquals("run-prune-9", allRuns[0].id)
        assertEquals("run-prune-5", allRuns[4].id)
    }
}
