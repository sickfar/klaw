package io.github.klaw.engine.memory

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.message.MessageRepository
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class DailyConsolidationServiceFactory {
    @Singleton
    fun dailyConsolidationService(
        config: EngineConfig,
        messageRepository: MessageRepository,
        memoryService: MemoryService,
        llmRouter: LlmRouter,
    ): DailyConsolidationService = DailyConsolidationService(config, messageRepository, memoryService, llmRouter)
}
