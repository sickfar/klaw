package io.github.klaw.engine.agent

import io.github.klaw.common.config.AgentConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.cancel

private val logger = KotlinLogging.logger {}

/**
 * Bundles all per-agent state and services. Created by [AgentContextFactory].
 * Call [shutdown] to release resources (DB, scheduler, heartbeat).
 */
class AgentContext(
    val agentId: String,
    val agentConfig: AgentConfig,
    val services: AgentServices = AgentServices(),
) {
    // Convenience accessors delegating to services
    val database get() = services.database
    val driver get() = services.driver
    val sessionManager get() = services.sessionManager
    val messageRepository get() = services.messageRepository
    val memoryService get() = services.memoryService
    val workspaceLoader get() = services.workspaceLoader
    val contextBuilder get() = services.contextBuilder
    val scheduler get() = services.scheduler
    val skillRegistry get() = services.skillRegistry
    val toolRegistry get() = services.toolRegistry
    val autoRagService get() = services.autoRagService
    val heartbeatRunner get() = services.heartbeatRunner
    val heartbeatScope get() = services.heartbeatScope
    val summaryService get() = services.summaryService
    val summaryRepository get() = services.summaryRepository
    val compactionRunner get() = services.compactionRunner
    val compactionTracker get() = services.compactionTracker
    val subagentRunRepository get() = services.subagentRunRepository
    val backupService get() = services.backupService
    val mcpToolRegistry get() = services.mcpToolRegistry
    val messageEmbeddingService get() = services.messageEmbeddingService
    val subagentHistoryLoader get() = services.subagentHistoryLoader

    @Volatile
    private var shutdownDone = false

    fun shutdown() {
        if (shutdownDone) return
        shutdownDone = true
        logger.info { "Shutting down agent: $agentId" }
        runCatching { services.scheduler?.shutdownBlocking() }
            .onFailure { logger.warn(it) { "Scheduler shutdown failed for agent $agentId" } }
        runCatching { services.backupService?.stop() }
            .onFailure { logger.warn(it) { "Backup stop failed for agent $agentId" } }
        runCatching { services.heartbeatScope?.cancel() }
            .onFailure { logger.warn(it) { "Heartbeat scope cancel failed for agent $agentId" } }
        runCatching { services.driver?.close() }
            .onFailure { logger.warn(it) { "DB driver close failed for agent $agentId" } }
        logger.debug { "Agent shut down: $agentId" }
    }
}
