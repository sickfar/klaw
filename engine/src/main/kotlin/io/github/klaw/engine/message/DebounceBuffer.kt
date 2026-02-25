package io.github.klaw.engine.message

import io.github.klaw.common.protocol.InboundSocketMessage
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
 */
class DebounceBuffer(
    private val debounceMs: Long,
    private val scope: CoroutineScope,
    private val onFlush: suspend (List<InboundSocketMessage>) -> Unit,
) {
    private val buffers = mutableMapOf<String, MutableList<InboundSocketMessage>>()
    private val timers = mutableMapOf<String, Job>()
    private val mutex = Mutex()

    /**
     * Adds [message] to the accumulator for its chatId and restarts the debounce timer.
     */
    suspend fun add(message: InboundSocketMessage) {
        mutex.withLock {
            val chatId = message.chatId
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
                        onFlush(messages)
                    }
                }
        }
    }
}
