package io.github.klaw.engine.tools

import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.common.protocol.ApprovalResponseMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.SocketMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

class ApprovalService(
    private val sendMessage: suspend (SocketMessage) -> Unit,
) {
    private data class PendingEntry(
        val chatId: String,
        val deferred: CompletableDeferred<Boolean>,
    )

    private val pending = ConcurrentHashMap<String, PendingEntry>()

    suspend fun requestApproval(
        chatId: String,
        command: String,
        riskScore: Int,
        timeoutMin: Int,
    ): Boolean {
        val id = "apr_${UUID.randomUUID()}"
        val deferred = CompletableDeferred<Boolean>()
        pending[id] = PendingEntry(chatId, deferred)

        val request =
            ApprovalRequestMessage(
                id = id,
                chatId = chatId,
                command = command,
                riskScore = riskScore,
                timeout = timeoutMin * SECONDS_PER_MINUTE,
            )
        sendMessage(request)
        logger.debug { "Approval request sent: id=$id, chatId=$chatId" }

        return try {
            if (timeoutMin <= 0) {
                deferred.await()
            } else {
                withTimeout(timeoutMin.minutes) { deferred.await() }
            }
        } catch (_: TimeoutCancellationException) {
            logger.debug { "Approval request timed out: id=$id" }
            false
        } finally {
            pending.remove(id)
        }
    }

    fun handleResponse(response: ApprovalResponseMessage) {
        val entry = pending[response.id]
        if (entry != null) {
            entry.deferred.complete(response.approved)
            logger.debug { "Approval response handled: id=${response.id}, approved=${response.approved}" }
        } else {
            logger.trace { "Ignoring approval response for unknown id=${response.id}" }
        }
    }

    fun denyPendingForChatId(chatId: String): List<String> {
        val deniedIds = mutableListOf<String>()
        for ((id, entry) in pending) {
            if (entry.chatId == chatId) {
                logger.debug { "Auto-denying pending approval for chatId=$chatId" }
                entry.deferred.complete(false)
                deniedIds.add(id)
            }
        }
        return deniedIds
    }

    suspend fun notify(
        chatId: String,
        channel: String,
        command: String,
    ) {
        sendMessage(
            OutboundSocketMessage(
                channel = channel,
                chatId = chatId,
                content = "Executing: `$command`",
            ),
        )
        logger.trace { "Notification sent for chatId=$chatId" }
    }

    companion object {
        private const val SECONDS_PER_MINUTE = 60
    }
}
