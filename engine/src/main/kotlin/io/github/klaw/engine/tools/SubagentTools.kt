package io.github.klaw.engine.tools

import io.github.klaw.engine.message.MessageProcessor
import io.github.klaw.engine.message.ScheduledMessage
import jakarta.inject.Provider
import jakarta.inject.Singleton

@Singleton
class SubagentTools(
    private val processorProvider: Provider<MessageProcessor>,
) {
    suspend fun spawn(
        name: String,
        message: String,
        model: String? = null,
        injectInto: String? = null,
    ): String {
        val scheduled = ScheduledMessage(name = name, message = message, model = model, injectInto = injectInto)
        processorProvider.get().handleScheduledMessage(scheduled)
        return "Субагент '$name' запущен"
    }
}
