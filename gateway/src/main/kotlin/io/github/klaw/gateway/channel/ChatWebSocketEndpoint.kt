package io.github.klaw.gateway.channel

import io.github.klaw.common.protocol.ChatFrame
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentType
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import jakarta.inject.Singleton
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

@Singleton
class ChatWebSocketEndpoint(
    private val localWsChannel: LocalWsChannel,
    private val uploadStore: UploadStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun install(routing: Route) {
        routing.webSocket("/chat") {
            logger.debug { "Local WS WebSocket connected" }
            localWsChannel.registerSession(this)
            try {
                for (frame in incoming) {
                    if (frame is Frame.Text) {
                        handleFrame(frame.readText())
                    }
                }
            } finally {
                localWsChannel.clearSession(this)
                logger.debug { "Local WS WebSocket closed" }
            }
        }
        routing.post("/upload") {
            handleUpload(call)
        }
    }

    /**
     * Handles image upload. Expects raw binary body with:
     * - Content-Type: image/png (or jpeg, gif, webp)
     * - X-Filename: original filename (optional)
     *
     * Returns JSON: {"id": "uuid"}
     */
    private suspend fun handleUpload(call: io.ktor.server.application.ApplicationCall) {
        val contentType = call.request.contentType().toString()
        if (!contentType.startsWith("image/")) {
            call.respondText(
                """{"error":"unsupported content type, expected image/*"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }

        val filename = call.request.header("X-Filename") ?: "upload.${contentType.substringAfter('/')}"
        val bytes =
            call.request
                .receiveChannel()
                .toInputStream()
                .readBytes()

        if (bytes.isEmpty()) {
            call.respondText(
                """{"error":"empty body"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return
        }

        val uploaded = uploadStore.save(bytes, filename, contentType)
        logger.debug { "Upload accepted: id=${uploaded.id}, size=${bytes.size}" }

        call.respondText(
            json.encodeToString(UploadResponse(uploaded.id)),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }

    private suspend fun io.ktor.server.websocket.DefaultWebSocketServerSession.handleFrame(message: String) {
        val chatFrame = decodeFrame(message) ?: return
        when (chatFrame.type) {
            "user" -> {
                logger.trace { "Local WS frame received: ${chatFrame.content.length} chars" }
                val attachmentPaths = resolveAttachmentIds(chatFrame.attachments.orEmpty())
                localWsChannel.handleIncoming(chatFrame.content, this, attachmentPaths)
            }

            "approval_response" -> {
                val id = chatFrame.approvalId
                val approved = chatFrame.approved
                if (id != null && approved != null) {
                    localWsChannel.resolveApproval(id, approved)
                } else {
                    logger.warn { "Malformed approval_response frame" }
                }
            }
        }
    }

    private fun resolveAttachmentIds(ids: List<String>): List<String> {
        val resolved = uploadStore.resolveAll(ids)
        if (resolved.size < ids.size) {
            val missing = ids.size - resolved.size
            logger.warn { "Could not resolve $missing attachment ID(s) — uploads may have expired" }
        }
        return resolved.map { it.path.toString() }
    }

    private fun decodeFrame(message: String): ChatFrame? =
        try {
            json.decodeFromString<ChatFrame>(message)
        } catch (_: SerializationException) {
            logger.warn { "ChatWebSocketEndpoint: malformed frame len=${message.length}" }
            null
        }

    @kotlinx.serialization.Serializable
    private data class UploadResponse(
        val id: String,
    )
}
