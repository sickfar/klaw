package io.github.klaw.engine.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AgentConfig
import io.github.klaw.engine.context.CompactionRunner
import io.github.klaw.engine.context.CompactionTracker
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.FileSkillRegistry
import io.github.klaw.engine.context.FileSummaryService
import io.github.klaw.engine.context.KlawWorkspaceLoader
import io.github.klaw.engine.context.SubagentHistoryLoader
import io.github.klaw.engine.context.SummaryRepository
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.db.BackupService
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.mcp.McpToolRegistry
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.message.MessageEmbeddingService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.tools.SubagentRunRepository
import io.github.klaw.engine.workspace.HeartbeatRunner
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Bundles all per-agent state and services. Created by [AgentContextFactory].
 * Call [shutdown] to release resources (DB, scheduler, heartbeat).
 */
class AgentContext(
    val agentId: String,
    val agentConfig: AgentConfig,
    val database: KlawDatabase? = null,
    val driver: JdbcSqliteDriver? = null,
    val sessionManager: SessionManager? = null,
    val messageRepository: MessageRepository? = null,
    val memoryService: MemoryService? = null,
    val workspaceLoader: KlawWorkspaceLoader? = null,
    val contextBuilder: ContextBuilder? = null,
    val scheduler: KlawScheduler? = null,
    val skillRegistry: FileSkillRegistry? = null,
    val toolRegistry: ToolRegistry? = null,
    val autoRagService: AutoRagService? = null,
    val heartbeatRunner: HeartbeatRunner? = null,
    val summaryService: FileSummaryService? = null,
    val summaryRepository: SummaryRepository? = null,
    val compactionRunner: CompactionRunner? = null,
    val compactionTracker: CompactionTracker? = null,
    val subagentRunRepository: SubagentRunRepository? = null,
    val backupService: BackupService? = null,
    val mcpToolRegistry: McpToolRegistry? = null,
    val messageEmbeddingService: MessageEmbeddingService? = null,
    val subagentHistoryLoader: SubagentHistoryLoader? = null,
) {
    @Volatile
    private var shutdownDone = false

    fun shutdown() {
        if (shutdownDone) return
        shutdownDone = true
        logger.info { "Shutting down agent: $agentId" }
        runCatching { scheduler?.shutdownBlocking() }
            .onFailure { logger.warn(it) { "Scheduler shutdown failed for agent $agentId" } }
        runCatching { backupService?.stop() }
            .onFailure { logger.warn(it) { "Backup stop failed for agent $agentId" } }
        runCatching { driver?.close() }
            .onFailure { logger.warn(it) { "DB driver close failed for agent $agentId" } }
        logger.debug { "Agent shut down: $agentId" }
    }
}
