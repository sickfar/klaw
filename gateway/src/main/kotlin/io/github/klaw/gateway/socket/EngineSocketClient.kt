package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.PingMessage
import io.github.klaw.common.protocol.PongMessage
import io.github.klaw.common.protocol.RegisterMessage
import io.github.klaw.common.protocol.RestartRequestSocketMessage
import io.github.klaw.common.protocol.ShutdownMessage
import io.github.klaw.common.protocol.SocketMessage
import io.github.klaw.common.protocol.StreamDeltaSocketMessage
import io.github.klaw.common.protocol.StreamEndSocketMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import java.net.InetSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

@Suppress("TooManyFunctions")
class EngineSocketClient(
    private val host: String,
    private val port: Int,
    private val buffer: GatewayBuffer,
    private val outboundHandler: OutboundMessageHandler,
    private val maxReconnectAttempts: Int = 0,
    private val drainBudgetMs: Long = 0,
    private val onReconnectExhausted: (() -> Unit)? = null,
) {
    private companion object {
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 60_000L
        const val PING_INTERVAL_MS = 60_000L
        const val NANOS_PER_MS = 1_000_000L
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
        var consecutiveFailures = 0
        while (true) {
            try {
                logger.debug { "Attempting to connect to engine at $host:$port" }
                connectAndRun()
                backoff = INITIAL_BACKOFF_MS
                consecutiveFailures = 0
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                consecutiveFailures++
                if (maxReconnectAttempts > 0 && consecutiveFailures >= maxReconnectAttempts) {
                    logger.error { "Engine reconnect limit ($maxReconnectAttempts) reached, giving up" }
                    onReconnectExhausted?.invoke()
                    return
                }
            }
            connected = false
            logger.info { "Engine socket disconnected" }
            logger.debug { "Reconnecting in ${backoff}ms" }
            delay(backoff)
            backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
        }
    }

    private suspend fun connectAndRun() {
        val addr = InetSocketAddress(host, port)
        val ch =
            withContext(Dispatchers.IO) {
                SocketChannel.open().apply { connect(addr) }
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

        // Launch periodic ping to keep connection alive and prevent engine idle timeout
        val pingJob = launchPingLoop()
        try {
            // Read messages from engine until connection closes or shutdown received
            processIncomingMessages(reader)
        } finally {
            pingJob.cancel()
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun launchPingLoop(): Job =
        scope.launch {
            while (connected) {
                delay(PING_INTERVAL_MS)
                if (!connected) break
                try {
                    writerLock.withLock {
                        writer?.println(json.encodeToString<SocketMessage>(PingMessage))
                    }
                    logger.trace { "Ping sent to engine" }
                } catch (_: Exception) {
                    logger.trace { "Ping send failed, connection likely dropped" }
                    break
                }
            }
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

                is ApprovalRequestMessage -> {
                    logger.debug { "Received approval request from engine" }
                    outboundHandler.handleApprovalRequest(msg)
                    false
                }

                is ShutdownMessage -> {
                    logger.debug { "Received shutdown from engine" }
                    outboundHandler.handleShutdown()
                    true
                }

                is RestartRequestSocketMessage -> {
                    logger.debug { "Received restart request from engine" }
                    outboundHandler.handleRestartRequest()
                    false
                }

                is StreamDeltaSocketMessage -> {
                    logger.trace { "Received stream delta: ${msg.delta.length} chars" }
                    outboundHandler.handleStreamDelta(msg)
                    false
                }

                is StreamEndSocketMessage -> {
                    logger.trace { "Received stream end: streamId=${msg.streamId}" }
                    outboundHandler.handleStreamEnd(msg)
                    false
                }

                is PongMessage -> {
                    logger.trace { "Pong received from engine" }
                    false
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
        if (buffer.isEmpty()) return
        val messages = buffer.drain()
        logger.debug { "Draining ${messages.size} buffered messages" }
        val startNanos = System.nanoTime()
        for ((index, msg) in messages.withIndex()) {
            if (isDrainBudgetExceeded(startNanos, index, messages)) return
            if (!sendBufferedMessage(msg)) {
                // Connection broken — re-buffer remaining messages
                messages.subList(index + 1, messages.size).forEach { buffer.append(it) }
                return
            }
        }
    }

    private fun isDrainBudgetExceeded(
        startNanos: Long,
        index: Int,
        messages: List<SocketMessage>,
    ): Boolean {
        if (drainBudgetMs <= 0) return false
        val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MS
        if (elapsedMs <= drainBudgetMs) return false
        val remaining = messages.subList(index, messages.size)
        remaining.forEach { buffer.append(it) }
        logger.warn {
            "Drain budget exceeded after $index/${messages.size} messages, " +
                "re-buffered ${remaining.size}"
        }
        return true
    }

    // Returns true if sent successfully, false if connection is broken
    private fun sendBufferedMessage(msg: SocketMessage): Boolean =
        try {
            writerLock.withLock {
                writer?.println(json.encodeToString<SocketMessage>(msg))
                logger.trace { "Drained buffered message: ${msg::class.simpleName}" }
            }
            true
        } catch (_: Exception) {
            buffer.append(msg)
            false
        }
}
