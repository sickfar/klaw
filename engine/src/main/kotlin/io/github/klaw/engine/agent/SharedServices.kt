package io.github.klaw.engine.agent

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.docs.DocsService
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.memory.EmbeddingService
import io.github.klaw.engine.message.MessageProcessor
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.tools.ActiveSubagentJobs
import io.github.klaw.engine.tools.ApprovalService
import io.github.klaw.engine.tools.DockerClient
import io.github.klaw.engine.tools.EngineHealthProvider
import io.github.klaw.engine.tools.ShutdownController
import io.github.klaw.engine.tools.WebFetchTool
import io.github.klaw.engine.tools.WebSearchTool
import jakarta.inject.Provider

/**
 * Services shared across all agents. These are instantiated once globally
 * and passed into each [AgentContext] via the [AgentContextFactory].
 */
@Suppress("LongParameterList")
class SharedServices(
    val llmRouter: LlmRouter,
    val embeddingService: EmbeddingService,
    val sqliteVecLoader: SqliteVecLoader,
    val globalConfig: EngineConfig,
    val engineHealthProvider: EngineHealthProvider,
    val dockerClient: DockerClient? = null,
    val approvalService: ApprovalService? = null,
    val shutdownController: ShutdownController? = null,
    val scheduler: KlawScheduler? = null,
    val docsService: DocsService? = null,
    val socketServerProvider: Provider<EngineSocketServer>? = null,
    val processorProvider: Provider<MessageProcessor>? = null,
    val activeSubagentJobs: ActiveSubagentJobs? = null,
    val webFetchTool: WebFetchTool? = null,
    val webSearchTool: WebSearchTool? = null,
)
