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
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Factory
import jakarta.inject.Provider
import jakarta.inject.Singleton

@Factory
class SharedServicesFactory {
    @Singleton
    @Suppress("LongParameterList")
    fun sharedServices(
        llmRouter: LlmRouter,
        embeddingService: EmbeddingService,
        sqliteVecLoader: SqliteVecLoader,
        config: EngineConfig,
        engineHealthProvider: EngineHealthProvider,
        dockerClient: DockerClient,
        approvalService: ApprovalService,
        shutdownController: ShutdownController,
        scheduler: KlawScheduler,
        docsService: DocsService,
        socketServerProvider: Provider<EngineSocketServer>,
        processorProvider: Provider<MessageProcessor>,
        activeSubagentJobs: ActiveSubagentJobs,
        webFetchTool: WebFetchTool,
        webSearchTool: WebSearchTool,
        applicationContext: ApplicationContext,
    ): SharedServices =
        SharedServices(
            llmRouter = llmRouter,
            embeddingService = embeddingService,
            sqliteVecLoader = sqliteVecLoader,
            globalConfig = config,
            engineHealthProvider = engineHealthProvider,
            dockerClient = dockerClient,
            approvalService = approvalService,
            shutdownController = shutdownController,
            scheduler = scheduler,
            docsService = docsService,
            socketServerProvider = socketServerProvider,
            processorProvider = processorProvider,
            activeSubagentJobs = activeSubagentJobs,
            webFetchTool = webFetchTool,
            webSearchTool = webSearchTool,
            applicationContext = applicationContext,
        )

    @Singleton
    fun agentContextFactory(sharedServices: SharedServices): AgentContextFactory = AgentContextFactory(sharedServices)
}
