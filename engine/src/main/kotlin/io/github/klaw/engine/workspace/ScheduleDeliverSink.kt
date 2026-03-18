package io.github.klaw.engine.workspace

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

class ScheduleDeliverSink {
    private val message = AtomicReference<String?>(null)

    @Volatile
    var lastDeliveredMessage: String? = null
        private set

    fun deliver(text: String) {
        lastDeliveredMessage = text
        message.set(text)
    }

    fun consumeMessage(): String? = message.getAndSet(null)
}

class ScheduleDeliverContext(
    val sink: ScheduleDeliverSink,
) : AbstractCoroutineContextElement(ScheduleDeliverContext) {
    companion object Key : CoroutineContext.Key<ScheduleDeliverContext>
}
