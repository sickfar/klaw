package io.github.klaw.engine.agent

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.memory.EmbeddingService
import io.github.klaw.engine.tools.EngineHealthProvider
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class SharedServicesFactory {
    @Singleton
    fun sharedServices(
        llmRouter: LlmRouter,
        embeddingService: EmbeddingService,
        sqliteVecLoader: SqliteVecLoader,
        config: EngineConfig,
        engineHealthProvider: EngineHealthProvider,
    ): SharedServices = SharedServices(llmRouter, embeddingService, sqliteVecLoader, config, engineHealthProvider)

    @Singleton
    fun agentContextFactory(sharedServices: SharedServices): AgentContextFactory = AgentContextFactory(sharedServices)
}
