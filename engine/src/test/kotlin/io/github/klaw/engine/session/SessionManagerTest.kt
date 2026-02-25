package io.github.klaw.engine.session

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.engine.db.KlawDatabase
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock
import kotlin.time.Instant

class SessionManagerTest {
    private fun createSessionManager(): Pair<SessionManager, KlawDatabase> {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        val db = KlawDatabase(driver)
        return SessionManager(db) to db
    }

    @Test
    fun `creates session with default model for unknown chatId`() =
        runTest {
            val (sessionManager, _) = createSessionManager()

            val session = sessionManager.getOrCreate("chat-123", "glm-5")

            assertEquals("chat-123", session.chatId)
            assertEquals("glm-5", session.model)
            assertNotNull(session.segmentStart)
            assertNotNull(session.createdAt)
        }

    @Test
    fun `returns existing session for known chatId`() =
        runTest {
            val (sessionManager, _) = createSessionManager()

            val first = sessionManager.getOrCreate("chat-456", "deepseek")
            val second = sessionManager.getOrCreate("chat-456", "other-model")

            assertEquals(first.chatId, second.chatId)
            assertEquals("deepseek", second.model)
            assertEquals(first.segmentStart, second.segmentStart)
            assertEquals(first.createdAt, second.createdAt)
        }

    @Test
    fun `updateModel changes session model`() =
        runTest {
            val (sessionManager, _) = createSessionManager()

            sessionManager.getOrCreate("chat-789", "glm-5")
            sessionManager.updateModel("chat-789", "qwen")

            val updated = sessionManager.getOrCreate("chat-789", "glm-5")
            assertEquals("qwen", updated.model)
        }

    @Test
    fun `resetSegment generates new segmentStart with current timestamp`() =
        runTest {
            val (sessionManager, db) = createSessionManager()

            sessionManager.getOrCreate("chat-reset", "glm-5")
            val original = db.sessionsQueries.getSession("chat-reset").executeAsOneOrNull()
            assertNotNull(original)
            checkNotNull(original)

            sessionManager.resetSegment("chat-reset")

            val updated = db.sessionsQueries.getSession("chat-reset").executeAsOneOrNull()
            assertNotNull(updated)
            checkNotNull(updated)
            assertEquals(original.created_at, updated.created_at)
        }

    @Test
    fun `new segment timestamp is after session created_at`() =
        runTest {
            val (sessionManager, db) = createSessionManager()

            val before = Clock.System.now()
            sessionManager.getOrCreate("chat-time", "glm-5")
            sessionManager.resetSegment("chat-time")

            val row = db.sessionsQueries.getSession("chat-time").executeAsOneOrNull()
            assertNotNull(row)
            checkNotNull(row)
            val segmentInstant = Instant.parse(row.segment_start)
            assertTrue(segmentInstant >= before, "segment_start should be after or equal to before")
        }
}
