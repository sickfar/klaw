package io.github.klaw.engine.workspace

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class HeartbeatDeliverSink {
    private val message = AtomicReference<String?>(null)

    fun deliver(text: String) {
        message.set(text)
    }

    fun consumeMessage(): String? = message.getAndSet(null)
}

class HeartbeatDeliverContext(
    val sink: HeartbeatDeliverSink,
) : AbstractCoroutineContextElement(HeartbeatDeliverContext) {
    companion object Key : CoroutineContext.Key<HeartbeatDeliverContext>
}
