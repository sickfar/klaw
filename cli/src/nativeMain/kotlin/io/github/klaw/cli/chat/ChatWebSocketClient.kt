package io.github.klaw.cli.chat

import io.github.klaw.common.protocol.ChatFrame
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/** Abstraction over WebSocket session for testability. */
internal interface ChatSession {
    suspend fun connect(
        onFrame: suspend (ChatFrame) -> Unit,
        outgoing: ReceiveChannel<ChatFrame>,
    )

    fun close()
}

/** Ktor CIO WebSocket client implementing ChatSession. */
internal class ChatWebSocketClient(
    private val url: String,
) : ChatSession {
    private val client =
        HttpClient(CIO) {
            install(WebSockets) {
                pingIntervalMillis = PING_INTERVAL_MS
            }
        }
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun connect(
        onFrame: suspend (ChatFrame) -> Unit,
        outgoing: ReceiveChannel<ChatFrame>,
    ) {
        client.webSocket(url) {
            val sendJob =
                launch {
                    for (chatFrame in outgoing) {
                        send(Frame.Text(json.encodeToString(ChatFrame.serializer(), chatFrame)))
                    }
                }
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        runCatching { json.decodeFromString(ChatFrame.serializer(), frame.readText()) }
                            .onSuccess { onFrame(it) }
                    }
                }
            } finally {
                sendJob.cancel()
            }
        }
    }

    override fun close() {
        client.close()
    }

    private companion object {
        const val PING_INTERVAL_MS = 30_000L
    }
}
