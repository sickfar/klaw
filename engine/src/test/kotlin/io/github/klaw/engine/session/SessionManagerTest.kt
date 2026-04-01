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

    @Test
    fun `getOrCreate updates updatedAt when session exists`() =
        runTest {
            val (sessionManager, _) = createSessionManager()

            val first = sessionManager.getOrCreate("chat-touch", "glm-5")
            assertNotNull(first.updatedAt)
            val firstUpdatedAt = first.updatedAt

            // Re-access — updatedAt should be >= first
            val second = sessionManager.getOrCreate("chat-touch", "glm-5")
            assertTrue(
                second.updatedAt >= firstUpdatedAt,
                "updatedAt should be >= first access, was ${second.updatedAt} vs $firstUpdatedAt",
            )
        }

    @Test
    fun `listSessions returns sessions with updatedAt`() =
        runTest {
            val (sessionManager, _) = createSessionManager()

            sessionManager.getOrCreate("chat-list-1", "glm-5")
            sessionManager.getOrCreate("chat-list-2", "deepseek")

            val sessions = sessionManager.listSessions()
            assertEquals(2, sessions.size)
            sessions.forEach { session ->
                assertNotNull(session.updatedAt, "Each session should have updatedAt")
            }
        }

    @Test
    fun `listActiveSessions filters by threshold`() =
        runTest {
            val (sessionManager, _) = createSessionManager()

            sessionManager.getOrCreate("chat-active", "glm-5")

            // With threshold in the past — session should be found
            val pastThreshold = Clock.System.now() - kotlin.time.Duration.parse("PT1H")
            val active = sessionManager.listActiveSessions(pastThreshold)
            assertTrue(active.isNotEmpty(), "Session should be found with past threshold")

            // With threshold in the future — session should NOT be found
            val futureThreshold = Clock.System.now() + kotlin.time.Duration.parse("PT1H")
            val inactive = sessionManager.listActiveSessions(futureThreshold)
            assertTrue(inactive.isEmpty(), "No sessions should be found with future threshold")
        }

    @Test
    fun `cleanupSessions removes old sessions`() =
        runTest {
            val (sessionManager, _) = createSessionManager()

            sessionManager.getOrCreate("chat-cleanup", "glm-5")
            assertEquals(1, sessionManager.listSessions().size)

            // Cleanup with future threshold — removes everything
            val futureThreshold = Clock.System.now() + kotlin.time.Duration.parse("PT1H")
            val deleted = sessionManager.cleanupSessions(futureThreshold)
            assertTrue(deleted > 0, "Should have deleted sessions")
            assertEquals(0, sessionManager.listSessions().size, "No sessions should remain")
        }

    @Test
    fun `cleanupSessions returns count of deleted`() =
        runTest {
            val (sessionManager, _) = createSessionManager()

            sessionManager.getOrCreate("chat-count-1", "glm-5")
            sessionManager.getOrCreate("chat-count-2", "deepseek")
            sessionManager.getOrCreate("chat-count-3", "qwen")

            val futureThreshold = Clock.System.now() + kotlin.time.Duration.parse("PT1H")
            val deleted = sessionManager.cleanupSessions(futureThreshold)
            assertEquals(3, deleted, "Should report 3 deleted sessions")
        }

    @Test
    fun `getTokenCount returns sum from messages table`() =
        runTest {
            val (sessionManager, db) = createSessionManager()

            sessionManager.getOrCreate("chat-tokens", "glm-5")

            // Insert messages with known token counts directly
            db.messagesQueries.insertMessage(
                id = "msg-1",
                channel = "console",
                chat_id = "chat-tokens",
                role = "user",
                type = "chat",
                content = "hello",
                metadata = null,
                created_at = Clock.System.now().toString(),
                tokens = 10,
            )
            db.messagesQueries.insertMessage(
                id = "msg-2",
                channel = "console",
                chat_id = "chat-tokens",
                role = "assistant",
                type = "chat",
                content = "world",
                metadata = null,
                created_at = Clock.System.now().toString(),
                tokens = 25,
            )

            val totalTokens = sessionManager.getTokenCount("chat-tokens")
            assertEquals(35L, totalTokens, "Should sum tokens from both messages")
        }

    @Test
    fun `getTokenCount returns zero for session with no messages`() =
        runTest {
            val (sessionManager, _) = createSessionManager()

            sessionManager.getOrCreate("chat-no-msgs", "glm-5")
            val totalTokens = sessionManager.getTokenCount("chat-no-msgs")
            assertEquals(0L, totalTokens, "Should return 0 when no messages exist")
        }

    @Test
    fun `getSession returns null for unknown chatId`() =
        runTest {
            val (sessionManager, _) = createSessionManager()

            val result = sessionManager.getSession("chat-unknown-xyz")

            assertTrue(result == null, "Should return null for unknown chatId")
        }

    @Test
    fun `getSession returns session without updating updatedAt`() =
        runTest {
            val (sessionManager, db) = createSessionManager()

            sessionManager.getOrCreate("chat-readonly", "glm-5")
            val originalRow = db.sessionsQueries.getSession("chat-readonly").executeAsOneOrNull()
            assertNotNull(originalRow)
            checkNotNull(originalRow)
            val originalUpdatedAt = originalRow.updated_at

            val result = sessionManager.getSession("chat-readonly")

            assertNotNull(result)
            checkNotNull(result)
            assertEquals("chat-readonly", result.chatId)
            assertEquals("glm-5", result.model)

            // updatedAt in DB must NOT have changed
            val rowAfter = db.sessionsQueries.getSession("chat-readonly").executeAsOneOrNull()
            assertNotNull(rowAfter)
            checkNotNull(rowAfter)
            assertEquals(originalUpdatedAt, rowAfter.updated_at, "getSession must not update updated_at in DB")
        }

    @Test
    fun `getMostRecentSession returns null when no sessions`() =
        runTest {
            val (sessionManager, _) = createSessionManager()

            val result = sessionManager.getMostRecentSession()

            assertTrue(result == null, "Should return null when there are no sessions")
        }

    @Test
    fun `getMostRecentSession returns session with latest updatedAt`() =
        runTest {
            val (sessionManager, _) = createSessionManager()

            sessionManager.getOrCreate("chat-older", "glm-5")
            // Touch chat-newer a second time so its updatedAt is more recent
            sessionManager.getOrCreate("chat-newer", "deepseek")
            sessionManager.getOrCreate("chat-newer", "deepseek")

            val result = sessionManager.getMostRecentSession()

            assertNotNull(result)
            checkNotNull(result)
            assertEquals("chat-newer", result.chatId, "Should return the session with the latest updatedAt")
        }
}
