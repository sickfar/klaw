package io.github.klaw.engine.message

import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Per-chatId debounce accumulator.
 *
 * Each call to [add] restarts the debounce timer for the given chatId.
 * When the timer fires (no new messages arrive within [debounceMs]), [onFlush] is invoked
 * with all accumulated messages for that chatId in insertion order.
 *
 * Messages from different chatIds are tracked independently.
 *
 * @param debounceMs  Quiet period in milliseconds before a batch is flushed.
 * @param scope       Coroutine scope used to launch timer coroutines (pass TestScope in tests).
 * @param onFlush     Suspend callback invoked with the accumulated message list when the timer fires.
 * @param maxEntries  Maximum number of distinct chatIds tracked simultaneously. Messages from new
 *                    chatIds are dropped when this limit is reached (existing chatIds can still add).
 */
class DebounceBuffer(
    private val debounceMs: Long,
    private val scope: CoroutineScope,
    private val onFlush: suspend (List<InboundSocketMessage>) -> Unit,
    private val maxEntries: Int = 1000,
) {
    private val buffers = mutableMapOf<String, MutableList<InboundSocketMessage>>()
    private val timers = mutableMapOf<String, Job>()
    private val mutex = Mutex()

    private val logger = KotlinLogging.logger {}

    /**
     * Adds [message] to the accumulator for its chatId and restarts the debounce timer.
     *
     * @return `true` if the message was accepted, `false` if rejected due to capacity limit.
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun add(message: InboundSocketMessage): Boolean {
        mutex.withLock {
            val chatId = message.chatId

            // Reject messages from new chatIds when at capacity
            if (chatId !in buffers && buffers.size >= maxEntries) {
                logger.warn { "DebounceBuffer at capacity ($maxEntries), dropping message from chatId=$chatId" }
                return false
            }

            buffers.getOrPut(chatId) { mutableListOf() }.add(message)

            // Cancel any in-flight timer for this chatId before starting a fresh one
            timers[chatId]?.cancel()

            timers[chatId] =
                scope.launch {
                    delay(debounceMs)
                    val messages =
                        mutex.withLock {
                            val msgs = buffers.remove(chatId)?.toList() ?: emptyList()
                            timers.remove(chatId)
                            msgs
                        }
                    if (messages.isNotEmpty()) {
                        try {
                            onFlush(messages)
                        } catch (e: Exception) {
                            logger.error(e) { "Error in onFlush callback for chatId=$chatId" }
                        }
                    }
                }
        }
        return true
    }
}
