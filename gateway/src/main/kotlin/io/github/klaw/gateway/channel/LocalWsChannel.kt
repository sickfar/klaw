package io.github.klaw.gateway.channel

import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.common.protocol.ChatFrame
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.websocket.WebSocketSession
import jakarta.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Clock
import kotlinx.coroutines.channels.Channel as KChannel

private val logger = KotlinLogging.logger {}

@Singleton
class LocalWsChannel(
    private val jsonlWriter: ConversationJsonlWriter,
) : Channel {
    override val name = "local_ws"

    @Volatile private var activeSession: WebSocketSession? = null
    override var onBecameAlive: (suspend () -> Unit)? = null
    private val incomingQueue = KChannel<IncomingMessage>(KChannel.UNLIMITED)
    private val pendingApprovals = ConcurrentHashMap<String, suspend (Boolean) -> Unit>()

    override fun isAlive(): Boolean = activeSession != null

    override suspend fun start() {
        // WebSocket server handles binding; nothing to do here
    }

    override suspend fun stop() {
        incomingQueue.close()
        try {
            activeSession?.close()
        } catch (_: Exception) {
            // ignore close errors
        }
        logger.info { "LocalWsChannel stopped" }
    }

    override suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit) {
        for (incoming in incomingQueue) {
            onMessage(incoming)
        }
    }

    suspend fun registerSession(session: WebSocketSession) {
        val wasAlive = activeSession != null
        activeSession = session
        if (!wasAlive) {
            onBecameAlive?.invoke()
            logger.debug { "Local WS session registered, channel became alive" }
        }
    }

    fun clearSession(session: WebSocketSession) {
        if (activeSession === session) {
            activeSession = null
            logger.debug { "Local WS session cleared" }
        }
    }

    suspend fun handleIncoming(
        content: String,
        session: WebSocketSession,
        attachmentPaths: List<String> = emptyList(),
    ) {
        activeSession = session
        val msgId = UUID.randomUUID().toString()
        val attachments =
            attachmentPaths.map { path ->
                AttachmentInfo(
                    path = path,
                    mimeType = detectMimeType(path),
                )
            }
        val incoming =
            IncomingMessage(
                id = msgId,
                channel = name,
                chatId = "local_ws_default",
                content = content,
                ts = Clock.System.now(),
                senderName = "User",
                chatType = "local",
                messageId = msgId,
                attachments = attachments,
            )
        jsonlWriter.writeInbound(incoming)
        incomingQueue.send(incoming)
        sendStatusFrame(session, "thinking")
        logger.trace { "Local WS message enqueued: ${content.length} chars, attachments=${attachments.size}" }
    }

    override suspend fun send(
        chatId: String,
        response: OutgoingMessage,
    ) {
        val session =
            activeSession ?: run {
                logger.warn { "LocalWsChannel.send: no active session" }
                return
            }
        sendStatusFrame(session, "")
        val message = Json.encodeToString(ChatFrame(type = "assistant", content = response.content))
        runCatching { session.sendSync(message) }
            .onFailure { e -> logger.error(e) { "LocalWsChannel: send failed" } }
    }

    override suspend fun sendApproval(
        chatId: String,
        request: ApprovalRequestMessage,
        onResult: suspend (Boolean) -> Unit,
    ) {
        val session =
            activeSession ?: run {
                logger.warn { "LocalWsChannel.sendApproval: no active session" }
                return
            }
        pendingApprovals[request.id] = onResult
        val message =
            Json.encodeToString(
                ChatFrame(
                    type = "approval_request",
                    content = request.command,
                    approvalId = request.id,
                    riskScore = request.riskScore,
                    timeout = request.timeout,
                ),
            )
        runCatching { session.sendSync(message) }
            .onFailure { e ->
                pendingApprovals.remove(request.id)
                logger.error(e) { "LocalWsChannel: sendApproval failed" }
            }
    }

    private fun sendStatusFrame(
        session: WebSocketSession,
        status: String,
    ) {
        val message = Json.encodeToString(ChatFrame(type = "status", content = status))
        runCatching { session.sendSync(message) }
            .onFailure { e -> logger.trace { "LocalWsChannel: status frame send failed: ${e::class.simpleName}" } }
    }

    suspend fun resolveApproval(
        id: String,
        approved: Boolean,
    ) {
        val callback =
            pendingApprovals.remove(id) ?: run {
                logger.debug { "No pending approval for id=$id" }
                return
            }
        callback(approved)
        logger.debug { "Approval resolved: approved=$approved" }
    }
}
