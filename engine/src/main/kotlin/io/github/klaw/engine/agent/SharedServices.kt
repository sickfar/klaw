package io.github.klaw.engine.agent

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.memory.EmbeddingService
import io.github.klaw.engine.tools.EngineHealthProvider

/**
 * Services shared across all agents. These are instantiated once globally
 * and passed into each [AgentContext] via the [AgentContextFactory].
 */
class SharedServices(
    val llmRouter: LlmRouter,
    val embeddingService: EmbeddingService,
    val sqliteVecLoader: SqliteVecLoader,
    val globalConfig: EngineConfig,
    val engineHealthProvider: EngineHealthProvider,
)
