package io.github.klaw.engine.socket

import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.RegisterMessage
import io.github.klaw.common.protocol.ShutdownMessage
import io.github.klaw.common.protocol.SocketMessage
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.StandardProtocolFamily
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.concurrent.atomic.AtomicInteger

class SocketProtocolLoopbackTest {
    private lateinit var server: EngineSocketServer
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    inner class CountingHandler : SocketMessageHandler {
        val inboundCount = AtomicInteger(0)
        val commandCount = AtomicInteger(0)
        val cliCount = AtomicInteger(0)

        override suspend fun handleInbound(message: InboundSocketMessage) {
            inboundCount.incrementAndGet()
        }

        override suspend fun handleCommand(message: CommandSocketMessage) {
            commandCount.incrementAndGet()
        }

        override suspend fun handleCliRequest(request: CliRequestMessage): String {
            cliCount.incrementAndGet()
            return """{"status":"ok","command":"${request.command}"}"""
        }
    }

    private lateinit var countingHandler: CountingHandler

    @BeforeEach
    fun setUp() {
        countingHandler = CountingHandler()
        server = EngineSocketServer(0, countingHandler)
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
    fun `gateway registers and receives outbound message`() =
        runBlocking {
            val client = connectClient()
            val writer = writerFor(client)
            val reader = readerFor(client)

            writer.println(json.encodeToString<SocketMessage>(RegisterMessage("gateway")))
            Thread.sleep(100)

            val outbound =
                OutboundSocketMessage(
                    channel = "telegram",
                    chatId = "chat-1",
                    content = "Hello from engine",
                )
            server.pushToGateway(outbound)

            val line = reader.readLine()
            assertNotNull(line)
            val received = json.decodeFromString<SocketMessage>(line!!)
            assertInstanceOf(OutboundSocketMessage::class.java, received)
            assertEquals("Hello from engine", (received as OutboundSocketMessage).content)

            client.close()
        }

    @Test
    fun `cli request-response cycle`() =
        runBlocking {
            val client = connectClient()
            val writer = writerFor(client)
            val reader = readerFor(client)

            val cliRequest = CliRequestMessage(command = "status", params = emptyMap())
            writer.println(json.encodeToString(cliRequest))

            val response = reader.readLine()
            assertNotNull(response)
            assertTrue(response!!.isNotBlank())

            client.close()
            Thread.sleep(50)

            assertEquals(1, countingHandler.cliCount.get())
        }

    @Test
    fun `multiple messages in flight`() =
        runBlocking {
            val client = connectClient()
            val writer = writerFor(client)

            writer.println(json.encodeToString<SocketMessage>(RegisterMessage("gateway")))
            Thread.sleep(100)

            repeat(3) { i ->
                val msg =
                    InboundSocketMessage(
                        id = "msg-$i",
                        channel = "telegram",
                        chatId = "chat-1",
                        content = "Message $i",
                        ts = "2024-01-01T00:00:00Z",
                    )
                writer.println(json.encodeToString<SocketMessage>(msg))
            }

            Thread.sleep(200)

            assertEquals(3, countingHandler.inboundCount.get())

            client.close()
        }

    @Test
    fun `shutdown message sent to gateway on stop`() =
        runBlocking {
            val client = connectClient()
            val writer = writerFor(client)
            val reader = readerFor(client)

            writer.println(json.encodeToString<SocketMessage>(RegisterMessage("gateway")))
            Thread.sleep(100)

            server.stop()
            Thread.sleep(100)

            val line = reader.readLine()
            assertNotNull(line)
            val received = json.decodeFromString<SocketMessage>(line!!)
            assertInstanceOf(ShutdownMessage::class.java, received)

            client.close()
        }
}
