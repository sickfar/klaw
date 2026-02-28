package io.github.klaw.engine.socket

import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.RegisterMessage
import io.github.klaw.common.protocol.ShutdownMessage
import io.github.klaw.common.protocol.SocketMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

@Suppress("TooManyFunctions")
class EngineSocketServer(
    private val socketPath: String,
    private val messageHandler: SocketMessageHandler,
    private val socketPerms: String = "rw-------",
) {
    companion object {
        const val HANDSHAKE_TIMEOUT_MS = 30_000L
        const val IDLE_TIMEOUT_MS = 300_000L
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var running = false

    @Volatile private var gatewayWriter: PrintWriter? = null
    private val writerLock = ReentrantLock()

    @Volatile private var serverChannel: ServerSocketChannel? = null

    fun start() {
        // Clean up stale socket file if present
        File(socketPath).let { if (it.exists()) it.delete() }
        // Ensure parent directories exist
        File(socketPath).parentFile?.mkdirs()

        val addr = UnixDomainSocketAddress.of(socketPath)
        val channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
        // Note: Brief TOCTOU window between bind() and setPosixFilePermissions() where socket
        // is accessible with default umask. Accepted risk: Unix socket is local-only and chmod 600
        // follows immediately. JVM has no umask API without JNI.
        channel.bind(addr)
        Files.setPosixFilePermissions(Path.of(socketPath), PosixFilePermissions.fromString(socketPerms))
        serverChannel = channel
        running = true
        scope.launch { acceptLoop() }
        logger.info { "EngineSocketServer started at $socketPath" }
    }

    fun stop() {
        running = false
        // Notify connected gateway before shutting down
        try {
            writerLock.withLock {
                gatewayWriter?.println(json.encodeToString<SocketMessage>(ShutdownMessage))
            }
        } catch (_: Exception) {
        }
        try {
            serverChannel?.close()
        } catch (_: Exception) {
        }
        File(socketPath).delete()
        scope.cancel()
        logger.info { "EngineSocketServer stopped" }
    }

    suspend fun pushToGateway(message: OutboundSocketMessage) {
        withContext(Dispatchers.IO) {
            writerLock.withLock {
                gatewayWriter?.println(json.encodeToString<SocketMessage>(message))
            }
        }
    }

    @Suppress("LoopWithTooManyJumpStatements", "TooGenericExceptionCaught")
    private suspend fun acceptLoop() {
        while (running) {
            try {
                val client = withContext(Dispatchers.IO) { serverChannel?.accept() } ?: continue
                logger.trace { "Incoming connection accepted from socket" }
                scope.launch { handleClient(client) }
            } catch (_: java.nio.channels.ClosedChannelException) {
                break
            } catch (_: kotlinx.coroutines.CancellationException) {
                break
            } catch (e: Exception) {
                logger.warn { "EngineSocketServer: accept error (continuing): ${e::class.simpleName}" }
                continue
            }
        }
    }

    @Suppress("ReturnCount")
    private suspend fun handleClient(channel: SocketChannel) {
        val reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel)))
        val writer = PrintWriter(Channels.newOutputStream(channel), true)

        try {
            val firstLine =
                try {
                    withTimeout(HANDSHAKE_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { readLineLimited(reader) }
                    }
                } catch (_: IllegalArgumentException) {
                    return
                } catch (_: TimeoutCancellationException) {
                    logger.warn { "EngineSocketServer: handshake timeout, closing connection" }
                    return
                } ?: return
            dispatchFirstMessage(firstLine, reader, writer)
        } finally {
            try {
                channel.close()
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun dispatchFirstMessage(
        firstLine: String,
        reader: BufferedReader,
        writer: PrintWriter,
    ) {
        try {
            val parsed = json.decodeFromString<SocketMessage>(firstLine)
            if (parsed is RegisterMessage) {
                handleGatewayConnection(reader, writer)
            } else {
                // Unexpected non-CLI SocketMessage type from non-gateway client
                writer.println("""{"error":"unexpected message type"}""")
            }
        } catch (_: SerializationException) {
            handleCliRequest(firstLine, writer)
        }
    }

    private suspend fun handleGatewayConnection(
        reader: BufferedReader,
        writer: PrintWriter,
    ) {
        // Gateway connection -- store writer for push operations
        writerLock.withLock {
            try {
                gatewayWriter?.close()
            } catch (_: Exception) {
            }
            gatewayWriter = writer
        }
        logger.debug { "Gateway connected" }
        // Read subsequent inbound/command messages in a loop
        @Suppress("LoopWithTooManyJumpStatements")
        while (running) {
            val line =
                try {
                    withTimeout(IDLE_TIMEOUT_MS) {
                        withContext(Dispatchers.IO) { readLineLimited(reader) }
                    }
                } catch (_: IllegalArgumentException) {
                    logger.warn { "EngineSocketServer: oversized message, skipping" }
                    continue
                } catch (_: TimeoutCancellationException) {
                    logger.warn { "EngineSocketServer: gateway idle timeout, closing connection" }
                    break
                } catch (_: java.io.IOException) {
                    // Socket closed (e.g. AsynchronousCloseException) — normal during shutdown
                    break
                } ?: break
            logger.trace { "Gateway line received: ${line.length} chars" }
            dispatchGatewayMessage(line)
        }
        logger.debug { "Gateway disconnected" }
    }

    private suspend fun dispatchGatewayMessage(line: String) {
        try {
            when (val inMsg = json.decodeFromString<SocketMessage>(line)) {
                is InboundSocketMessage -> {
                    logger.trace { "Gateway message dispatched: ${inMsg::class.simpleName}" }
                    messageHandler.handleInbound(inMsg)
                }

                is CommandSocketMessage -> {
                    logger.trace { "Gateway message dispatched: ${inMsg::class.simpleName}" }
                    messageHandler.handleCommand(inMsg)
                }

                else -> {} // Ignore other SocketMessage types sent by gateway
            }
        } catch (_: SerializationException) {
            logger.warn { "EngineSocketServer: malformed gateway message, skipping" }
        }
    }

    private fun readLineLimited(
        reader: BufferedReader,
        maxBytes: Int = 1_048_576,
    ): String? {
        val sb = StringBuilder()
        while (true) {
            val ch = reader.read()
            if (ch == -1) return if (sb.isEmpty()) null else sb.toString()
            if (ch == '\n'.code) return sb.toString()
            if (ch == '\r'.code) continue
            sb.append(ch.toChar())
            if (sb.length > maxBytes) {
                drainLine(reader)
                throw IllegalArgumentException("Line exceeds max length ($maxBytes bytes)")
            }
        }
    }

    private fun drainLine(reader: BufferedReader) {
        while (true) {
            val c = reader.read()
            if (c == -1 || c == '\n'.code) break
        }
    }

    private suspend fun handleCliRequest(
        firstLine: String,
        writer: PrintWriter,
    ) {
        // CLI framing path -- CliRequestMessage is not a SocketMessage subclass
        try {
            val request = json.decodeFromString<CliRequestMessage>(firstLine)
            logger.debug { "CLI request handled: ${request.command}" }
            val response = messageHandler.handleCliRequest(request)
            writer.println(response)
        } catch (_: SerializationException) {
            writer.println("""{"error":"invalid request"}""")
        }
    }
}
