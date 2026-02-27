package io.github.klaw.gateway.channel

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.ConsoleConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.micronaut.websocket.WebSocketSession
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ChatWebSocketServerTest {
    @TempDir
    lateinit var tempDir: File

    private fun makeConsoleChannel(): ConsoleChannel = ConsoleChannel(ConversationJsonlWriter(tempDir.absolutePath))

    private fun makeConfig(
        enabled: Boolean = true,
        port: Int = 37474,
    ): GatewayConfig = GatewayConfig(ChannelsConfig(console = ConsoleConfig(enabled = enabled, port = port)))

    private fun configNoConsole(): GatewayConfig = GatewayConfig(ChannelsConfig())

    private fun mockSession(): WebSocketSession = mockk(relaxed = true) { every { id } returns "sess-1" }

    @Test
    fun `onOpen when console is enabled allows connection`() =
        runBlocking {
            val session = mockSession()
            val server = ChatWebSocketServer(makeConsoleChannel(), makeConfig(enabled = true))

            server.onOpen(session)

            verify(exactly = 0) { session.close(any()) }
        }

    @Test
    fun `onOpen when console is disabled closes connection immediately`() =
        runBlocking {
            val session = mockSession()
            val server = ChatWebSocketServer(makeConsoleChannel(), makeConfig(enabled = false))

            server.onOpen(session)

            verify(exactly = 1) { session.close(any()) }
        }

    @Test
    fun `onOpen when console config is absent closes connection`() =
        runBlocking {
            val session = mockSession()
            val server = ChatWebSocketServer(makeConsoleChannel(), configNoConsole())

            server.onOpen(session)

            verify(exactly = 1) { session.close(any()) }
        }

    @Test
    fun `onMessage with valid ChatFrame type=user and enabled delegates to ConsoleChannel`() =
        runBlocking {
            val session = mockSession()
            val consoleChannel = mockk<ConsoleChannel>(relaxed = true)
            val server = ChatWebSocketServer(consoleChannel, makeConfig(enabled = true))

            server.onMessage("""{"type":"user","content":"hello"}""", session)

            coVerify(exactly = 1) { consoleChannel.handleIncoming("hello", session) }
        }

    @Test
    fun `onMessage when console is disabled is ignored`() =
        runBlocking {
            val session = mockSession()
            val consoleChannel = mockk<ConsoleChannel>(relaxed = true)
            val server = ChatWebSocketServer(consoleChannel, makeConfig(enabled = false))

            server.onMessage("""{"type":"user","content":"hello"}""", session)

            coVerify(exactly = 0) { consoleChannel.handleIncoming(any(), any()) }
        }

    @Test
    fun `onMessage with malformed JSON is silently ignored`() =
        runBlocking {
            val session = mockSession()
            val consoleChannel = mockk<ConsoleChannel>(relaxed = true)
            val server = ChatWebSocketServer(consoleChannel, makeConfig(enabled = true))

            server.onMessage("not-valid-json{{{", session)

            coVerify(exactly = 0) { consoleChannel.handleIncoming(any(), any()) }
        }

    @Test
    fun `onMessage with type not equal to user is ignored`() =
        runBlocking {
            val session = mockSession()
            val consoleChannel = mockk<ConsoleChannel>(relaxed = true)
            val server = ChatWebSocketServer(consoleChannel, makeConfig(enabled = true))

            server.onMessage("""{"type":"assistant","content":"should be ignored"}""", session)

            coVerify(exactly = 0) { consoleChannel.handleIncoming(any(), any()) }
        }

    @Test
    fun `onMessage with empty content is forwarded when type=user`() =
        runBlocking {
            val session = mockSession()
            val consoleChannel = mockk<ConsoleChannel>(relaxed = true)
            val server = ChatWebSocketServer(consoleChannel, makeConfig(enabled = true))

            server.onMessage("""{"type":"user","content":""}""", session)

            coVerify(exactly = 1) { consoleChannel.handleIncoming("", session) }
        }

    @Test
    fun `onClose does not throw`() =
        runBlocking {
            val session = mockSession()
            val server = ChatWebSocketServer(makeConsoleChannel(), makeConfig(enabled = true))

            // Should not throw
            server.onClose(session)
        }

    @Test
    fun `onError does not throw`() {
        val session = mockSession()
        val server = ChatWebSocketServer(makeConsoleChannel(), makeConfig(enabled = true))

        // Should not throw
        server.onError(session, RuntimeException("test error"))
    }
}
