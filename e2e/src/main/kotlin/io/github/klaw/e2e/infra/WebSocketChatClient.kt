package io.github.klaw.e2e.infra

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

private val logger = KotlinLogging.logger {}

class WebSocketChatClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val httpClient =
        HttpClient(CIO) {
            install(WebSockets)
        }

    private var session: WebSocketSession? = null
    private val incomingFrames = Channel<ChatFrame>(Channel.UNLIMITED)

    @Volatile
    private var connected = false

    private fun connect(
        host: String,
        port: Int,
    ) = runBlocking {
        httpClient.webSocket("ws://$host:$port/chat") {
            session = this
            connected = true
            logger.debug { "WebSocket connected to $host:$port" }

            // Launch reader coroutine
            val readerJob =
                launch {
                    for (frame in incoming) {
                        if (frame is Frame.Text) {
                            val text = frame.readText()
                            try {
                                val chatFrame = json.decodeFromString<ChatFrame>(text)
                                incomingFrames.send(chatFrame)
                            } catch (e: SerializationException) {
                                logger.warn { "Failed to decode frame: ${e::class.simpleName}" }
                            } catch (e: IllegalArgumentException) {
                                logger.warn { "Failed to decode frame: ${e::class.simpleName}" }
                            }
                        }
                    }
                }

            // This suspends until close() is called
            readerJob.join()
        }
    }

    fun connectAsync(
        host: String,
        port: Int,
    ) {
        // Close existing session if reconnecting
        val existingSession = session
        if (existingSession != null) {
            runBlocking { existingSession.close() }
            session = null
            connected = false
        }

        val thread =
            Thread {
                try {
                    connect(host, port)
                } catch (e: IOException) {
                    logger.debug { "WebSocket connection ended: ${e::class.simpleName}" }
                } catch (e: IllegalStateException) {
                    logger.debug { "WebSocket connection ended: ${e::class.simpleName}" }
                }
            }
        thread.isDaemon = true
        thread.name = "ws-chat-client"
        thread.start()

        // Wait for connection to establish
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (!connected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
        }
        if (!connected) {
            error("WebSocket connection timed out")
        }
    }

    fun sendMessage(text: String) =
        runBlocking {
            val frame = ChatFrame(type = "user", content = text)
            session?.send(Frame.Text(json.encodeToString(frame)))
                ?: error("Not connected")
            logger.debug { "Sent user message (length=${text.length})" }
        }

    fun sendCommand(command: String) {
        sendMessage("/$command")
    }

    fun sendCommandAndReceive(
        command: String,
        timeoutMs: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
    ): String {
        sendCommand(command)
        return waitForAssistantResponse(timeoutMs)
    }

    fun drainFrames() {
        var drained = 0
        while (true) {
            val frame = incomingFrames.tryReceive().getOrNull() ?: break
            drained++
            logger.debug { "Drained frame: type=${frame.type}" }
        }
        if (drained > 0) {
            logger.debug { "Drained $drained pending frames" }
        }
    }

    fun waitForAssistantResponse(timeoutMs: Long = DEFAULT_RESPONSE_TIMEOUT_MS): String =
        runBlocking {
            withTimeout(timeoutMs) {
                var result: String? = null
                while (result == null) {
                    val frame = incomingFrames.receive()
                    when (frame.type) {
                        "assistant" -> result = frame.content
                        "status" -> logger.debug { "Status: ${frame.content}" }
                        else -> logger.debug { "Frame type: ${frame.type}" }
                    }
                }
                result
            }
        }

    fun sendApprovalResponse(
        approvalId: String,
        approved: Boolean,
    ) = runBlocking {
        val frame = ChatFrame(type = "approval_response", approvalId = approvalId, approved = approved)
        session?.send(Frame.Text(json.encodeToString(frame)))
            ?: error("Not connected")
        logger.debug { "Sent approval response (approvalId=$approvalId, approved=$approved)" }
    }

    fun waitForApprovalRequest(timeoutMs: Long = DEFAULT_RESPONSE_TIMEOUT_MS): ChatFrame =
        runBlocking {
            val buffered = mutableListOf<ChatFrame>()
            try {
                withTimeout(timeoutMs) {
                    var result: ChatFrame? = null
                    while (result == null) {
                        val frame = incomingFrames.receive()
                        if (frame.type == "approval_request") {
                            result = frame
                        } else {
                            buffered.add(frame)
                            logger.debug { "Buffered non-approval frame: type=${frame.type}" }
                        }
                    }
                    result
                }
            } finally {
                // Re-enqueue buffered frames so they are not lost
                for (frame in buffered) {
                    incomingFrames.send(frame)
                }
            }
        }

    fun collectFrames(timeoutMs: Long): List<ChatFrame> =
        runBlocking {
            val collected = mutableListOf<ChatFrame>()
            try {
                withTimeout(timeoutMs) {
                    while (true) {
                        val frame = incomingFrames.receive()
                        collected.add(frame)
                    }
                }
            } catch (_: TimeoutCancellationException) {
                // Expected — timeout means collection period is over
            }
            collected
        }

    fun sendAndReceive(
        text: String,
        timeoutMs: Long = DEFAULT_RESPONSE_TIMEOUT_MS,
    ): String {
        sendMessage(text)
        return waitForAssistantResponse(timeoutMs)
    }

    fun disconnect() =
        runBlocking {
            session?.close()
            session = null
            connected = false
            logger.debug { "WebSocket session disconnected" }
        }

    fun reconnect(
        host: String,
        port: Int,
    ) {
        disconnect()
        drainFrames()
        connectAsync(host, port)
    }

    fun close() =
        runBlocking {
            session?.close()
            session = null
            httpClient.close()
            connected = false
            logger.debug { "WebSocket client closed" }
        }

    companion object {
        private const val DEFAULT_RESPONSE_TIMEOUT_MS = 30_000L
        private const val CONNECT_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 100L
    }
}
