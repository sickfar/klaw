package io.github.klaw.engine.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

val kotlinx.coroutines.Dispatchers.VT: CoroutineDispatcher
    get() = VirtualThreadDispatcher

private object VirtualThreadDispatcher : CoroutineDispatcher() {
    override fun dispatch(
        context: CoroutineContext,
        block: Runnable,
    ) {
        Thread.startVirtualThread(block)
    }

    override fun toString() = "Dispatchers.VT"
}
