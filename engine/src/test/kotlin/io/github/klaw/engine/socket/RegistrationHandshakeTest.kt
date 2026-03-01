package io.github.klaw.engine.socket

import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.RegisterMessage
import io.github.klaw.common.protocol.SocketMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList

class RegistrationHandshakeTest {
    private lateinit var server: EngineSocketServer
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    inner class CapturingHandler : SocketMessageHandler {
        val capturedInbound = CopyOnWriteArrayList<InboundSocketMessage>()
        val capturedCommands = CopyOnWriteArrayList<CommandSocketMessage>()
        val cliRequests = CopyOnWriteArrayList<CliRequestMessage>()

        override suspend fun handleInbound(message: InboundSocketMessage) {
            capturedInbound.add(message)
        }

        override suspend fun handleCommand(message: CommandSocketMessage) {
            capturedCommands.add(message)
        }

        override suspend fun handleCliRequest(request: CliRequestMessage): String {
            cliRequests.add(request)
            return """{"status":"ok"}"""
        }
    }

    private lateinit var capturingHandler: CapturingHandler

    @BeforeEach
    fun setUp() {
        capturingHandler = CapturingHandler()
        server = EngineSocketServer(0, capturingHandler)
        server.start()
        Thread.sleep(100)
    }

    @AfterEach
    fun tearDown() {
        server.stop()
        Thread.sleep(50)
    }

    private fun connectClient(): SocketChannel {
        val addr = InetSocketAddress("127.0.0.1", server.actualPort)
        return SocketChannel.open(StandardProtocolFamily.INET).apply { connect(addr) }
    }

    private fun writerFor(channel: SocketChannel): PrintWriter = PrintWriter(Channels.newOutputStream(channel), true)

    @Suppress("MaxLineLength")
    private fun readerFor(channel: SocketChannel): BufferedReader = BufferedReader(InputStreamReader(Channels.newInputStream(channel)))

    @Test
    fun `gateway registers with type=register client=gateway`() =
        runBlocking {
            val client = connectClient()
            val writer = writerFor(client)

            writer.println(json.encodeToString<SocketMessage>(RegisterMessage("gateway")))
            Thread.sleep(200)

            val inbound =
                InboundSocketMessage(
                    id = "test-1",
                    channel = "telegram",
                    chatId = "chat-99",
                    content = "hello",
                    ts = "2024-01-01T00:00:00Z",
                )
            writer.println(json.encodeToString<SocketMessage>(inbound))
            Thread.sleep(150)

            assertEquals(1, capturingHandler.capturedInbound.size)
            assertEquals("test-1", capturingHandler.capturedInbound[0].id)
            assertEquals(0, capturingHandler.cliRequests.size)

            client.close()
        }

    @Test
    fun `cli connection does not send RegisterMessage`() =
        runBlocking {
            val client = connectClient()
            val writer = writerFor(client)
            val reader = readerFor(client)

            val cliReq = CliRequestMessage(command = "ping", params = mapOf("key" to "val"))
            writer.println(json.encodeToString(cliReq))

            val response = reader.readLine()
            assertNotNull(response)

            Thread.sleep(100)

            assertEquals(1, capturingHandler.cliRequests.size)
            assertEquals("ping", capturingHandler.cliRequests[0].command)
            assertEquals(0, capturingHandler.capturedInbound.size)

            client.close()
        }

    @Test
    fun `engine tracks single gateway connection second register replaces first`() =
        runBlocking {
            val client1 = connectClient()
            val writer1 = writerFor(client1)

            writer1.println(json.encodeToString<SocketMessage>(RegisterMessage("gateway")))
            Thread.sleep(100)

            val client2 = connectClient()
            val writer2 = writerFor(client2)
            val reader2 = readerFor(client2)

            writer2.println(json.encodeToString<SocketMessage>(RegisterMessage("gateway")))
            Thread.sleep(100)

            val outbound =
                OutboundSocketMessage(
                    channel = "telegram",
                    chatId = "chat-1",
                    content = "To second gateway",
                )
            server.pushToGateway(outbound)
            Thread.sleep(100)

            val line2 = reader2.readLine()
            assertNotNull(line2)
            val received2 = json.decodeFromString<SocketMessage>(line2!!)
            assertInstanceOf(OutboundSocketMessage::class.java, received2)
            assertEquals("To second gateway", (received2 as OutboundSocketMessage).content)

            client1.close()
            client2.close()
        }
}
