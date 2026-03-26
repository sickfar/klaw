package io.github.klaw.gateway.channel

import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.micronaut.websocket.WebSocketSession
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LocalWsChannelStreamTest {
    @TempDir
    lateinit var tempDir: File

    private fun makeChannel(): LocalWsChannel = LocalWsChannel(ConversationJsonlWriter(tempDir.absolutePath))

    private fun mockSession(): WebSocketSession = mockk(relaxed = true)

    @Test
    fun `sendStreamDelta sends ChatFrame with type=stream_delta`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            channel.registerSession(session)

            channel.sendStreamDelta("local_ws_default", "Hello ", "stream-1")

            verify {
                session.sendSync(
                    match<String> {
                        it.contains("\"type\":\"stream_delta\"") && it.contains("Hello ")
                    },
                )
            }
        }

    @Test
    fun `sendStreamEnd sends status clear and ChatFrame with type=stream_end`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            channel.registerSession(session)

            channel.sendStreamEnd("local_ws_default", "Hello world!", "stream-1")

            // Verify both frames were sent
            verify(atLeast = 1) {
                session.sendSync(
                    match<String> { it.contains("\"type\":\"status\"") },
                )
            }
            verify(atLeast = 1) {
                session.sendSync(
                    match<String> { it.contains("\"type\":\"stream_end\"") },
                )
            }
        }

    @Test
    fun `sendStreamDelta with no active session does not crash`() =
        runBlocking {
            val channel = makeChannel()
            // No session registered
            channel.sendStreamDelta("local_ws_default", "data", "stream-1")
            // Should not throw
        }

    @Test
    fun `sendStreamEnd with no active session does not crash`() =
        runBlocking {
            val channel = makeChannel()
            // No session registered
            channel.sendStreamEnd("local_ws_default", "full content", "stream-1")
            // Should not throw
        }

    @Test
    fun `sendStreamDelta multiple deltas each sent as separate frame`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            channel.registerSession(session)

            channel.sendStreamDelta("local_ws_default", "Hello ", "stream-1")
            channel.sendStreamDelta("local_ws_default", "world", "stream-1")
            channel.sendStreamDelta("local_ws_default", "!", "stream-1")

            verify(exactly = 3) {
                session.sendSync(
                    match<String> { it.contains("\"type\":\"stream_delta\"") },
                )
            }
        }
}
