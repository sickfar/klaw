package io.github.klaw.engine.socket

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.init.InitCliHandler
import io.github.klaw.engine.maintenance.ReindexService
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.SessionManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliRequestDispatchTest {
    private val initCliHandler = mockk<InitCliHandler>()
    private val klawScheduler = mockk<KlawScheduler>()
    private val memoryService = mockk<MemoryService>()
    private val reindexService = mockk<ReindexService>(relaxed = true)

    private fun inMemoryDb(): KlawDatabase {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        return KlawDatabase(driver)
    }

    private fun buildDispatcher(sessionManager: SessionManager = SessionManager(inMemoryDb())): CliCommandDispatcher =
        CliCommandDispatcher(
            initCliHandler = initCliHandler,
            sessionManager = sessionManager,
            klawScheduler = klawScheduler,
            memoryService = memoryService,
            reindexService = reindexService,
        )

    @Test
    fun `status command returns session info`() =
        runTest {
            val dispatcher = buildDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage(command = "status", params = emptyMap()))
            assertTrue(result.contains("ok"), "Expected 'ok' in: $result")
        }

    @Test
    fun `sessions command returns active sessions`() =
        runTest {
            val db = inMemoryDb()
            val sessionManager = SessionManager(db)
            // Pre-create a session
            sessionManager.getOrCreate("chat-1", "test/model")

            val dispatcher = buildDispatcher(sessionManager)
            val result = dispatcher.dispatch(CliRequestMessage(command = "sessions", params = emptyMap()))
            assertTrue(result.contains("chat-1"), "Expected 'chat-1' in: $result")
        }

    @Test
    fun `schedule_list command returns jobs`() =
        runTest {
            coEvery { klawScheduler.list() } returns "[{\"name\":\"daily\"}]"
            val dispatcher = buildDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage(command = "schedule_list", params = emptyMap()))
            assertTrue(result.contains("daily"), "Expected 'daily' in: $result")
        }

    @Test
    fun `schedule_add command creates job`() =
        runTest {
            coEvery { klawScheduler.add("morning", "0 8 * * *", "Good morning", null, null) } returns "OK: added"
            val dispatcher = buildDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage(
                        command = "schedule_add",
                        params = mapOf("name" to "morning", "cron" to "0 8 * * *", "message" to "Good morning"),
                    ),
                )
            assertTrue(result.contains("added"), "Expected 'added' in: $result")
        }

    @Test
    fun `schedule_remove command removes job`() =
        runTest {
            coEvery { klawScheduler.remove("morning") } returns "OK: removed"
            val dispatcher = buildDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage(
                        command = "schedule_remove",
                        params = mapOf("name" to "morning"),
                    ),
                )
            assertTrue(result.contains("removed"), "Expected 'removed' in: $result")
        }

    @Test
    fun `memory_search command returns results`() =
        runTest {
            coEvery { memoryService.search("AI", any()) } returns "[{\"chunk\":\"Interesting AI fact\"}]"
            val dispatcher = buildDispatcher()
            val result =
                dispatcher.dispatch(
                    CliRequestMessage(
                        command = "memory_search",
                        params = mapOf("query" to "AI"),
                    ),
                )
            assertTrue(result.contains("AI"), "Expected 'AI' in: $result")
        }

    @Test
    fun `reindex command returns status`() =
        runTest {
            val dispatcher = buildDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage(command = "reindex", params = emptyMap()))
            assertTrue(result.isNotEmpty(), "Expected non-empty result from reindex")
        }

    @Test
    fun `unknown command returns error JSON`() =
        runTest {
            val dispatcher = buildDispatcher()
            val result = dispatcher.dispatch(CliRequestMessage(command = "nonexistent_cmd", params = emptyMap()))
            assertTrue(result.contains("error"), "Expected 'error' in: $result")
            assertTrue(result.contains("nonexistent_cmd"), "Expected command name in: $result")
        }
}
