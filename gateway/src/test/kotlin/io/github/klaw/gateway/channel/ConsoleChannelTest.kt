package io.github.klaw.gateway.channel

import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.micronaut.websocket.WebSocketSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate

class ConsoleChannelTest {
    @TempDir
    lateinit var tempDir: File

    private fun makeChannel(): ConsoleChannel = ConsoleChannel(ConversationJsonlWriter(tempDir.absolutePath))

    private fun mockSession(): WebSocketSession =
        mockk(relaxed = true) {
            every { id } returns "test-session-id"
        }

    @Test
    fun `handleIncoming creates IncomingMessage with channel=console and chatId=console_default`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            val received = mutableListOf<IncomingMessage>()

            val listenJob = launch { channel.listen { msg -> received += msg } }

            channel.handleIncoming("hello", session)
            channel.stop()
            listenJob.join()

            assertEquals(1, received.size)
            assertEquals("console", received[0].channel)
            assertEquals("console_default", received[0].chatId)
            assertEquals("hello", received[0].content)
        }

    @Test
    fun `handleIncoming writes inbound to JSONL`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()

            channel.handleIncoming("test message", session)
            channel.stop()

            val today = LocalDate.now().toString()
            val file = File(tempDir, "console_default/$today.jsonl")
            assertTrue(file.exists(), "JSONL file should exist at ${file.absolutePath}")
            val line = file.readLines().firstOrNull()
            assertNotNull(line)
            assertTrue(line!!.contains("test message"), "Expected message content in JSONL")
        }

    @Test
    fun `handleIncoming with no active listener does not crash`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()

            // Call handleIncoming without starting listen — queue is buffered, should not block
            channel.handleIncoming("hello", session)
            channel.stop()
        }

    @Test
    fun `send with active session sends JSON-encoded ChatFrame type=assistant`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()

            // Set active session via handleIncoming
            channel.handleIncoming("trigger", session)

            // Now send a response
            channel.send("console_default", OutgoingMessage("AI response"))

            // Verify sendSync was called with a JSON containing type=assistant
            verify {
                session.sendSync(
                    match<String> { it.contains("\"type\":\"assistant\"") && it.contains("AI response") },
                )
            }

            channel.stop()
        }

    @Test
    fun `send with no active session returns without crash`() =
        runBlocking {
            val channel = makeChannel()

            // No handleIncoming call, so activeSession is null
            channel.send("console_default", OutgoingMessage("response"))
            // Should not throw
        }

    @Test
    fun `listen suspends until stop is called`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            val received = mutableListOf<IncomingMessage>()

            val listenJob = launch { channel.listen { received += it } }

            channel.handleIncoming("msg1", session)
            channel.handleIncoming("msg2", session)
            channel.stop()
            listenJob.join()

            assertEquals(2, received.size)
        }

    @Test
    fun `stop closes incomingQueue so listen returns`() =
        runBlocking {
            val channel = makeChannel()
            var listenReturned = false

            val listenJob =
                launch {
                    channel.listen { }
                    listenReturned = true
                }

            channel.stop()
            listenJob.join()

            assertTrue(listenReturned, "listen() should return after stop()")
        }

    @Test
    fun `handleIncoming message has non-null unique id`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            val received = mutableListOf<IncomingMessage>()

            val listenJob = launch { channel.listen { received += it } }

            channel.handleIncoming("first", session)
            channel.handleIncoming("second", session)
            channel.stop()
            listenJob.join()

            assertEquals(2, received.size)
            assertTrue(received[0].id.isNotBlank())
            assertTrue(received[1].id.isNotBlank())
            assertTrue(received[0].id != received[1].id, "IDs should be unique")
        }
}
