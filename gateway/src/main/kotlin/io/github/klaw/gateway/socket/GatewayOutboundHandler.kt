package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.common.protocol.ApprovalResponseMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.SocketMessage
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.channel.OutgoingMessage
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.klaw.gateway.pairing.InboundAllowlistService
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.ApplicationContext
import jakarta.inject.Singleton
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

private const val RESTART_DELAY_MS = 500L

@Singleton
class GatewayOutboundHandler(
    private val channels: List<Channel>,
    private val allowlistService: InboundAllowlistService,
    private val jsonlWriter: ConversationJsonlWriter,
    private val applicationContext: ApplicationContext,
    approvalCallback: (suspend (SocketMessage) -> Unit)? = null,
) : OutboundMessageHandler {
    @Volatile
    var approvalCallback: (suspend (SocketMessage) -> Unit)? = approvalCallback

    internal var exitFn: (Int) -> Unit = { System.exit(it) }

    private val channelBuffers = ConcurrentHashMap<String, ArrayDeque<OutboundSocketMessage>>()
    private val bufferLock = ReentrantLock()

    init {
        channels.forEach { channel ->
            channel.onBecameAlive = { drainChannelBuffer(channel) }
        }
    }

    override suspend fun handleOutbound(message: OutboundSocketMessage) {
        if (!isAllowed(message.chatId, message.channel)) {
            logger.warn {
                "Outbound blocked: chatId=${message.chatId} not in allowedChats for channel=${message.channel}"
            }
            return
        }
        jsonlWriter.writeOutbound(
            chatId = message.chatId,
            content = message.content,
            model = message.meta?.get("model"),
        )
        val channel = channels.firstOrNull { it.name == message.channel }
        if (channel == null) {
            logger.warn { "No channel found for channel=${message.channel}" }
            return
        }
        if (!channel.isAlive()) {
            bufferMessage(channel.name, message)
            return
        }
        drainChannelBuffer(channel)
        try {
            channel.send(message.chatId, OutgoingMessage(message.content, message.replyTo))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.warn(e) { "Send failed for channel=${message.channel}, buffering message" }
            bufferMessage(channel.name, message)
            return
        }
        logger.debug { "Outbound dispatched to channel=${message.channel} chatId=${message.chatId}" }
    }

    override suspend fun handleApprovalRequest(message: ApprovalRequestMessage) {
        val chatId = message.chatId
        val channelName = detectChannel(chatId)
        if (!isAllowed(chatId, channelName)) {
            logger.warn { "Approval request blocked: chatId=$chatId not allowed" }
            return
        }
        val channel = channels.firstOrNull { it.name == channelName }
        if (channel == null) {
            logger.warn { "No channel found for approval request chatId=$chatId" }
            return
        }
        channel.sendApproval(chatId, message) { approved ->
            val response = ApprovalResponseMessage(id = message.id, approved = approved)
            approvalCallback?.invoke(response)
            logger.debug { "Approval response sent: id=${message.id} approved=$approved" }
        }
    }

    override suspend fun handleShutdown() {
        logger.debug { "Received shutdown signal from engine" }
    }

    override suspend fun handleRestartRequest() {
        logger.info { "Restart requested by engine — gateway shutting down" }
        Thread({
            Thread.sleep(RESTART_DELAY_MS)
            applicationContext.close()
            exitFn(0)
        }, "klaw-gateway-restart").apply { isDaemon = false }.start()
    }

    private fun bufferMessage(
        channelName: String,
        message: OutboundSocketMessage,
    ) {
        bufferLock.withLock {
            val deque = channelBuffers.getOrPut(channelName) { ArrayDeque() }
            if (deque.size >= MAX_CHANNEL_BUFFER_SIZE) {
                deque.removeFirst()
                logger.trace { "Channel buffer overflow for channel=$channelName, dropped oldest message" }
            }
            deque.addLast(message)
        }
        logger.trace { "Message buffered for channel=$channelName (${message::class.simpleName})" }
    }

    internal suspend fun drainChannelBuffer(channel: Channel) {
        val messages =
            bufferLock.withLock {
                val deque = channelBuffers[channel.name] ?: return
                val copy = ArrayList(deque)
                deque.clear()
                copy
            }
        if (messages.isEmpty()) return
        logger.debug { "Draining ${messages.size} buffered messages for channel=${channel.name}" }
        for ((index, msg) in messages.withIndex()) {
            try {
                channel.send(msg.chatId, OutgoingMessage(msg.content, msg.replyTo))
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.warn(e) { "Drain failed at message ${index + 1}/${messages.size} for channel=${channel.name}" }
                bufferLock.withLock {
                    val deque = channelBuffers.getOrPut(channel.name) { ArrayDeque() }
                    // Re-buffer the failed message and remaining
                    val remaining = messages.subList(index, messages.size)
                    for (rem in remaining) {
                        deque.addLast(rem)
                    }
                }
                return
            }
        }
    }

    private fun isAllowed(
        chatId: String,
        channel: String,
    ): Boolean = allowlistService.isChatAllowed(channel, chatId)

    private fun detectChannel(chatId: String): String =
        when {
            chatId.startsWith("telegram_") -> "telegram"
            chatId.startsWith("local_ws") -> "local_ws"
            else -> "unknown"
        }

    companion object {
        const val MAX_CHANNEL_BUFFER_SIZE = 100
    }
}
