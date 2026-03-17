package io.github.klaw.gateway.channel

import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocalWsChannelLivenessTest {
    @TempDir
    lateinit var tempDir: File

    private fun makeChannel(): LocalWsChannel = LocalWsChannel(ConversationJsonlWriter(tempDir.absolutePath))

    private fun mockSession(): DefaultWebSocketServerSession =
        mockk(relaxed = true) {
            coEvery { send(any<Frame>()) } returns Unit
        }

    @Test
    fun `isAlive returns false when no active session`() {
        val channel = makeChannel()
        assertFalse(channel.isAlive())
    }

    @Test
    fun `isAlive returns true after handleIncoming sets session`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()

            channel.handleIncoming("hello", session)

            assertTrue(channel.isAlive())
            channel.stop()
        }

    @Test
    fun `onBecameAlive callback fired when registerSession sets session`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            val latch = CountDownLatch(1)

            channel.onBecameAlive = { latch.countDown() }
            channel.registerSession(session)

            assertTrue(latch.await(2, TimeUnit.SECONDS), "onBecameAlive should be called")
            channel.stop()
        }

    @Test
    fun `onBecameAlive not fired when session already active`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()

            // Register first session
            channel.registerSession(session)

            var callbackCount = 0
            channel.onBecameAlive = { callbackCount++ }

            // Register again - should NOT fire callback (already alive)
            channel.registerSession(session)

            assertTrue(callbackCount == 0, "onBecameAlive should not fire when already alive")
            channel.stop()
        }

    @Test
    fun `clearSession makes channel not alive`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()

            channel.registerSession(session)
            assertTrue(channel.isAlive())

            channel.clearSession(session)
            assertFalse(channel.isAlive())
            channel.stop()
        }

    @Test
    fun `clearSession only clears matching session`() =
        runBlocking {
            val channel = makeChannel()
            val session1 = mockSession()
            val session2 = mockSession()

            channel.registerSession(session1)
            assertTrue(channel.isAlive())

            // Clearing a different session should not affect the active one
            channel.clearSession(session2)
            assertTrue(channel.isAlive())
            channel.stop()
        }
}
