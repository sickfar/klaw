package io.github.klaw.engine.socket

import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.RegisterMessage
import io.github.klaw.common.protocol.ShutdownMessage
import io.github.klaw.common.protocol.SocketMessage
import jakarta.annotation.PostConstruct
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

@Suppress("TooManyFunctions")
class EngineSocketServer(
    private val socketPath: String,
    private val messageHandler: SocketMessageHandler,
) {
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

    @PostConstruct
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
        // Restrict permissions to owner read/write only (600)
        Files.setPosixFilePermissions(Path.of(socketPath), PosixFilePermissions.fromString("rw-------"))
        serverChannel = channel
        running = true
        scope.launch { acceptLoop() }
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
                scope.launch { handleClient(client) }
            } catch (_: java.nio.channels.ClosedChannelException) {
                break
            } catch (_: kotlinx.coroutines.CancellationException) {
                break
            } catch (e: Exception) {
                System.err.println("EngineSocketServer: accept error (continuing): ${e.message}")
                continue
            }
        }
    }

    private suspend fun handleClient(channel: SocketChannel) {
        val reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel)))
        val writer = PrintWriter(Channels.newOutputStream(channel), true)

        try {
            val firstLine =
                try {
                    withContext(Dispatchers.IO) { readLineLimited(reader) }
                } catch (_: IllegalArgumentException) {
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
        // Read subsequent inbound/command messages in a loop
        @Suppress("LoopWithTooManyJumpStatements")
        while (running) {
            val line =
                try {
                    withContext(Dispatchers.IO) { readLineLimited(reader) }
                } catch (_: IllegalArgumentException) {
                    System.err.println("EngineSocketServer: oversized message, skipping")
                    continue
                } ?: break
            dispatchGatewayMessage(line)
        }
    }

    private suspend fun dispatchGatewayMessage(line: String) {
        try {
            when (val inMsg = json.decodeFromString<SocketMessage>(line)) {
                is InboundSocketMessage -> {
                    messageHandler.handleInbound(inMsg)
                }

                is CommandSocketMessage -> {
                    messageHandler.handleCommand(inMsg)
                }

                else -> {} // Ignore other SocketMessage types sent by gateway
            }
        } catch (_: SerializationException) {
            System.err.println("EngineSocketServer: malformed gateway message, skipping")
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
            val response = messageHandler.handleCliRequest(request)
            writer.println(response)
        } catch (_: SerializationException) {
            writer.println("""{"error":"invalid request"}""")
        }
    }
}
