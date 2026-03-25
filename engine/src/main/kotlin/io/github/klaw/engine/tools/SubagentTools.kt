package io.github.klaw.engine.tools

import io.github.klaw.engine.message.MessageProcessor
import io.github.klaw.engine.message.ScheduledMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Provider
import jakarta.inject.Singleton
import java.util.UUID
import kotlin.coroutines.coroutineContext

private val logger = KotlinLogging.logger {}

@Singleton
class SubagentTools(
    private val processorProvider: Provider<MessageProcessor>,
    private val repository: SubagentRunRepository,
) {
    suspend fun spawn(
        name: String,
        message: String,
        model: String? = null,
        injectInto: String? = null,
    ): String {
        val runId = UUID.randomUUID().toString()
        val ctx = coroutineContext[ChatContext]
        val sourceChatId = ctx?.chatId
        val sourceChannel = ctx?.channel

        repository.startRun(
            StartRunRequest(
                id = runId,
                name = name,
                model = model,
                injectInto = injectInto,
                sourceChatId = sourceChatId,
                sourceChannel = sourceChannel,
            ),
        )

        logger.info { "Subagent started: name=$name, runId=$runId" }
        logger.debug { "subagent_spawn: model=$model, injectInto=$injectInto, source=$sourceChatId" }

        val scheduled =
            ScheduledMessage(
                name = name,
                message = message,
                model = model,
                injectInto = injectInto,
                runId = runId,
                sourceChatId = sourceChatId,
                sourceChannel = sourceChannel,
            )
        processorProvider.get().handleScheduledMessage(scheduled)
        logger.trace { "subagent_spawn dispatched: runId=$runId" }
        return """{"id":"$runId","name":"$name","status":"RUNNING"}"""
    }
}
