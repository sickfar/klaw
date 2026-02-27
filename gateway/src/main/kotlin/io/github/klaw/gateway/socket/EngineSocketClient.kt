package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.RegisterMessage
import io.github.klaw.common.protocol.ShutdownMessage
import io.github.klaw.common.protocol.SocketMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

@Suppress("TooManyFunctions")
class EngineSocketClient(
    private val socketPath: String,
    private val buffer: GatewayBuffer,
    private val outboundHandler: OutboundMessageHandler,
) {
    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var connected = false

    private val writerLock = ReentrantLock()

    @Volatile private var writer: PrintWriter? = null
    private var channel: SocketChannel? = null

    fun start() {
        scope.launch { reconnectLoop() }
    }

    @PreDestroy
    fun stop() {
        connected = false
        try {
            channel?.close()
        } catch (_: Exception) {
        }
        scope.cancel()
    }

    fun send(message: SocketMessage): Boolean {
        if (!connected) {
            logger.trace { "Not connected, buffering message: ${message::class.simpleName}" }
            buffer.append(message)
            return false
        }
        return try {
            writerLock.withLock {
                val w = writer
                if (w == null) {
                    logger.trace { "Writer null, buffering message: ${message::class.simpleName}" }
                    buffer.append(message)
                    return@withLock false
                }
                w.println(json.encodeToString<SocketMessage>(message))
                logger.trace { "Message sent to engine: ${message::class.simpleName}" }
                true
            }
        } catch (_: Exception) {
            buffer.append(message)
            false
        }
    }

    private suspend fun reconnectLoop() {
        var backoff = INITIAL_BACKOFF_MS
        while (true) {
            try {
                logger.debug { "Attempting to connect to engine socket at $socketPath" }
                connectAndRun()
                backoff = INITIAL_BACKOFF_MS
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // connection failed or dropped -- will retry after backoff
            }
            connected = false
            logger.info { "Engine socket disconnected" }
            logger.debug { "Reconnecting in ${backoff}ms" }
            delay(backoff)
            backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
        }
    }

    private suspend fun connectAndRun() {
        val addr = UnixDomainSocketAddress.of(socketPath)
        val ch =
            withContext(Dispatchers.IO) {
                SocketChannel.open(StandardProtocolFamily.UNIX).apply { connect(addr) }
            }
        channel = ch
        val reader = BufferedReader(InputStreamReader(Channels.newInputStream(ch)))
        val w = PrintWriter(Channels.newOutputStream(ch), true)
        writer = w

        // Send registration first
        w.println(json.encodeToString<SocketMessage>(RegisterMessage(client = "gateway")))
        connected = true
        logger.info { "Engine socket connected" }

        // Drain any buffered messages now that we are connected
        drainBuffer()

        // Read messages from engine until connection closes or shutdown received
        processIncomingMessages(reader)
    }

    private suspend fun processIncomingMessages(reader: BufferedReader) {
        var shutdown = false
        while (!shutdown) {
            val line = withContext(Dispatchers.IO) { reader.readLine() } ?: break
            logger.trace { "Engine line received: ${line.length} chars" }
            shutdown = handleIncomingLine(line)
        }
    }

    private suspend fun handleIncomingLine(line: String): Boolean =
        try {
            when (val msg = json.decodeFromString<SocketMessage>(line)) {
                is OutboundSocketMessage -> {
                    logger.trace { "Received engine message: ${msg::class.simpleName}" }
                    outboundHandler.handleOutbound(msg)
                    false
                }

                is ShutdownMessage -> {
                    logger.debug { "Received shutdown from engine" }
                    outboundHandler.handleShutdown()
                    true
                }

                else -> {
                    false
                } // RegisterMessage, InboundSocketMessage, etc -- ignored from engine
            }
        } catch (_: SerializationException) {
            logger.warn { "EngineSocketClient: malformed engine message, skipping" }
            false
        }

    private fun drainBuffer() {
        if (!buffer.isEmpty()) {
            val messages = buffer.drain()
            logger.debug { "Draining ${messages.size} buffered messages" }
            messages.forEach { msg ->
                try {
                    writerLock.withLock {
                        writer?.println(json.encodeToString<SocketMessage>(msg))
                        logger.trace { "Drained buffered message: ${msg::class.simpleName}" }
                    }
                } catch (_: Exception) {
                    buffer.append(msg)
                }
            }
        }
    }
}
