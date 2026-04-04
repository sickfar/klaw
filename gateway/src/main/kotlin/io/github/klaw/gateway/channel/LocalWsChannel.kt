package io.github.klaw.gateway.channel

import io.github.klaw.common.config.GatewayConfig
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

/**
 * WebSocket channel supporting multiple agents via /ws/chat/{agentId}.
 * Each agentId has its own session and chatId (local_ws_{agentId}).
 */
@Singleton
class LocalWsChannel(
    private val jsonlWriter: ConversationJsonlWriter,
    private val config: GatewayConfig,
) : Channel {
    override val name: String
        get() =
            config.channels.websocket.keys
                .firstOrNull() ?: "local_ws"

    /** Sessions keyed by agentId. */
    private val sessions = ConcurrentHashMap<String, WebSocketSession>()

    /** Reverse lookup: chatId → agentId for outbound routing. */
    private val chatIdToAgent = ConcurrentHashMap<String, String>()

    override var onBecameAlive: (suspend () -> Unit)? = null
    private val incomingQueue = KChannel<IncomingMessage>(KChannel.UNLIMITED)
    private val pendingApprovals = ConcurrentHashMap<String, suspend (Boolean) -> Unit>()

    override fun isAlive(): Boolean = sessions.isNotEmpty()

    override suspend fun start() {
        // WebSocket server handles binding; nothing to do here
    }

    override suspend fun stop() {
        incomingQueue.close()
        sessions.values.forEach { session ->
            runCatching { session.close() }
        }
        sessions.clear()
        chatIdToAgent.clear()
        logger.info { "LocalWsChannel stopped" }
    }

    override suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit) {
        for (incoming in incomingQueue) {
            onMessage(incoming)
        }
    }

    suspend fun registerSession(
        agentId: String,
        session: WebSocketSession,
    ) {
        val previous = sessions.put(agentId, session)
        val chatId = chatIdFor(agentId)
        chatIdToAgent[chatId] = agentId
        if (previous !== session) {
            onBecameAlive?.invoke()
            logger.debug { "WS session registered for agent=$agentId chatId=$chatId" }
        }
    }

    fun clearSession(
        agentId: String,
        session: WebSocketSession,
    ) {
        if (sessions[agentId] === session) {
            sessions.remove(agentId)
            chatIdToAgent.remove(chatIdFor(agentId))
            logger.debug { "WS session cleared for agent=$agentId" }
        }
    }

    suspend fun handleIncoming(
        agentId: String,
        content: String,
        session: WebSocketSession,
        attachmentPaths: List<String> = emptyList(),
    ) {
        sessions[agentId] = session
        val chatId = chatIdFor(agentId)
        chatIdToAgent[chatId] = agentId
        val channelName = resolveChannelName(agentId)
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
                channel = channelName,
                chatId = chatId,
                content = content,
                ts = Clock.System.now(),
                agentId = agentId,
                senderName = "User",
                chatType = "local",
                messageId = msgId,
                attachments = attachments,
            )
        jsonlWriter.writeInbound(incoming)
        incomingQueue.send(incoming)
        sendStatusFrame(session, "thinking")
        logger.trace { "WS message enqueued: agent=$agentId ${content.length} chars" }
    }

    override suspend fun sendStreamDelta(
        chatId: String,
        delta: String,
        streamId: String,
    ) {
        val session = sessionFor(chatId) ?: return
        val message = Json.encodeToString(ChatFrame(type = "stream_delta", content = delta))
        runCatching { session.sendSync(message) }
            .onFailure { e -> logger.trace { "WS stream delta send failed: ${e::class.simpleName}" } }
    }

    override suspend fun sendStreamEnd(
        chatId: String,
        fullContent: String,
        streamId: String,
    ) {
        val session = sessionFor(chatId) ?: return
        sendStatusFrame(session, "")
        val message = Json.encodeToString(ChatFrame(type = "stream_end", content = fullContent))
        runCatching { session.sendSync(message) }
            .onFailure { e -> logger.error(e) { "WS stream end send failed" } }
    }

    override suspend fun send(
        chatId: String,
        response: OutgoingMessage,
    ) {
        val session = sessionFor(chatId) ?: return
        sendStatusFrame(session, "")
        val message = Json.encodeToString(ChatFrame(type = "assistant", content = response.content))
        runCatching { session.sendSync(message) }
            .onFailure { e -> logger.error(e) { "WS send failed for chatId=$chatId" } }
    }

    override suspend fun sendApproval(
        chatId: String,
        request: ApprovalRequestMessage,
        onResult: suspend (Boolean) -> Unit,
    ) {
        val session = sessionFor(chatId) ?: return
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
                logger.error(e) { "WS sendApproval failed" }
            }
    }

    override suspend fun dismissApproval(approvalId: String) {
        val callback = pendingApprovals.remove(approvalId) ?: return
        sessions.values.forEach { session ->
            val frame = Json.encodeToString(ChatFrame(type = "approval_dismiss", approvalId = approvalId))
            runCatching { session.sendSync(frame) }
        }
        callback(false)
        logger.debug { "Approval dismissed: id=$approvalId" }
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

    private fun sessionFor(chatId: String): WebSocketSession? {
        val agentId = chatIdToAgent[chatId]
        if (agentId == null) {
            logger.warn { "WS: no agent for chatId=$chatId" }
            return null
        }
        return sessions[agentId] ?: run {
            logger.warn { "WS: no session for agent=$agentId" }
            null
        }
    }

    private fun chatIdFor(agentId: String): String = "local_ws_$agentId"

    private fun resolveChannelName(agentId: String): String =
        config.channels.websocket.entries
            .firstOrNull { it.value.agentId == agentId }
            ?.key ?: name

    private fun sendStatusFrame(
        session: WebSocketSession,
        status: String,
    ) {
        val message = Json.encodeToString(ChatFrame(type = "status", content = status))
        runCatching { session.sendSync(message) }
            .onFailure { e -> logger.trace { "WS status frame send failed: ${e::class.simpleName}" } }
    }
}
