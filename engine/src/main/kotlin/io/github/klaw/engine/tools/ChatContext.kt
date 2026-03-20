package io.github.klaw.engine.tools

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

data class ChatContext(
    val chatId: String,
    val channel: String,
    val modelId: String = "",
) : AbstractCoroutineContextElement(ChatContext) {
    companion object Key : CoroutineContext.Key<ChatContext>
}
