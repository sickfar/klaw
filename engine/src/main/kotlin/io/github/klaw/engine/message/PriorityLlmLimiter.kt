package io.github.klaw.engine.message

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.LinkedList

/**
 * Rate limiter that grants LLM permits with priority:
 * interactive requests are always served before subagent requests.
 *
 * At most [maxPermits] concurrent operations are allowed.
 */
class PriorityLlmLimiter(
    private val maxPermits: Int,
) {
    private val mutex = Mutex()
    private var available = maxPermits
    private val interactiveQueue = LinkedList<CompletableDeferred<Unit>>()
    private val subagentQueue = LinkedList<CompletableDeferred<Unit>>()

    suspend fun <T> withInteractivePermit(block: suspend () -> T): T {
        acquire(interactive = true)
        try {
            return block()
        } finally {
            release()
        }
    }

    suspend fun <T> withSubagentPermit(block: suspend () -> T): T {
        acquire(interactive = false)
        try {
            return block()
        } finally {
            release()
        }
    }

    private suspend fun acquire(interactive: Boolean) {
        val deferred =
            mutex.withLock {
                if (available > 0) {
                    available--
                    return
                }
                val d = CompletableDeferred<Unit>()
                if (interactive) interactiveQueue.add(d) else subagentQueue.add(d)
                d
            }
        deferred.await()
    }

    private suspend fun release() {
        mutex.withLock {
            // Drain interactive queue first (priority)
            val next = interactiveQueue.poll() ?: subagentQueue.poll()
            if (next != null) {
                next.complete(Unit)
            } else {
                available++
            }
        }
    }
}
