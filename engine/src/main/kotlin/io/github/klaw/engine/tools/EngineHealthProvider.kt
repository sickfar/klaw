package io.github.klaw.engine.tools

import app.cash.sqldelight.db.SqlDriver
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.mcp.McpToolRegistry
import io.github.klaw.engine.memory.EmbeddingService
import io.github.klaw.engine.memory.OnnxEmbeddingService
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.socket.EngineOutboundBuffer
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.util.VT
import io.github.klaw.engine.workspace.HeartbeatRunnerFactory
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Provider
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.time.Duration
import java.time.Instant

private val logger = KotlinLogging.logger {}

@Serializable
data class SandboxHealth(
    val enabled: Boolean,
    @SerialName("keep_alive") val keepAlive: Boolean,
    @SerialName("container_active") val containerActive: Boolean,
    val executions: Int,
)

@Serializable
data class EngineHealth(
    @SerialName("gateway_status") val gatewayStatus: String,
    @SerialName("engine_uptime") val engineUptime: String,
    val docker: Boolean,
    val sandbox: SandboxHealth,
    @SerialName("mcp_servers") val mcpServers: List<String>,
    @SerialName("embedding_service") val embeddingService: String,
    @SerialName("sqlite_vec") val sqliteVec: Boolean,
    @SerialName("database_ok") val databaseOk: Boolean,
    @SerialName("scheduled_jobs") val scheduledJobs: Int,
    @SerialName("active_sessions") val activeSessions: Int,
    @SerialName("pending_deliveries") val pendingDeliveries: Int,
    @SerialName("heartbeat_running") val heartbeatRunning: Boolean,
    @SerialName("docs_enabled") val docsEnabled: Boolean,
    @SerialName("memory_facts") val memoryFacts: Int,
    @SerialName("running_subagents") val runningSubagents: Int,
)

data class ContextStatus(
    val gatewayConnected: Boolean,
    val uptime: Duration,
    val scheduledJobs: Int,
    val activeSessions: Int,
    val sandboxReady: Boolean,
    val embeddingType: String,
    val docker: Boolean,
)

@Singleton
class HealthInfrastructure(
    val socketServerProvider: Provider<EngineSocketServer>,
    val outboundBuffer: EngineOutboundBuffer,
    val scheduler: KlawScheduler,
    val sessionManager: SessionManager,
    val sandboxManager: SandboxManager,
    val mcpToolRegistry: McpToolRegistry,
)

@Singleton
class EngineHealthProvider(
    private val infra: HealthInfrastructure,
    private val embeddingService: EmbeddingService,
    private val sqliteVecLoader: SqliteVecLoader,
    private val driver: SqlDriver,
    private val heartbeatRunnerFactoryProvider: Provider<HeartbeatRunnerFactory>,
    private val config: EngineConfig,
    private val subagentRunRepository: SubagentRunRepository,
) {
    private var startedAt: Instant = Instant.now()

    fun markStarted() {
        startedAt = Instant.now()
        logger.debug { "Engine start time recorded" }
    }

    suspend fun getHealth(): EngineHealth {
        val jobCount = infra.scheduler.jobCount()
        val sessionCount = infra.sessionManager.listSessions().size
        val dbOk = checkDatabase()
        val chunks = countMemoryFacts()
        val runningSubagents = subagentRunRepository.countByStatus("RUNNING")

        return buildHealth(jobCount, sessionCount, dbOk, chunks, runningSubagents)
    }

    suspend fun getContextStatus(): ContextStatus =
        ContextStatus(
            gatewayConnected = infra.socketServerProvider.get().isGatewayConnected,
            uptime = Duration.between(startedAt, Instant.now()),
            scheduledJobs = infra.scheduler.jobCount(),
            activeSessions = infra.sessionManager.listSessions().size,
            sandboxReady = infra.sandboxManager.isContainerActive || !infra.sandboxManager.isKeepAlive,
            embeddingType = classifyEmbeddingService(),
            docker = isRunningInDocker(),
        )

    private fun buildHealth(
        jobCount: Int,
        sessionCount: Int,
        dbOk: Boolean,
        chunks: Int,
        runningSubagents: Int = 0,
    ): EngineHealth =
        EngineHealth(
            gatewayStatus = if (infra.socketServerProvider.get().isGatewayConnected) "connected" else "disconnected",
            engineUptime = Duration.between(startedAt, Instant.now()).toString(),
            docker = isRunningInDocker(),
            sandbox =
                SandboxHealth(
                    enabled = config.codeExecution.dockerImage.isNotBlank(),
                    keepAlive = infra.sandboxManager.isKeepAlive,
                    containerActive = infra.sandboxManager.isContainerActive,
                    executions = infra.sandboxManager.currentExecutionCount,
                ),
            mcpServers = infra.mcpToolRegistry.serverNames().toList(),
            embeddingService = classifyEmbeddingService(),
            sqliteVec = sqliteVecLoader.isAvailable(),
            databaseOk = dbOk,
            scheduledJobs = jobCount,
            activeSessions = sessionCount,
            pendingDeliveries = infra.outboundBuffer.pendingCount(),
            heartbeatRunning = heartbeatRunnerFactoryProvider.get().runner?.isRunning ?: false,
            docsEnabled = config.docs.enabled,
            memoryFacts = chunks,
            runningSubagents = runningSubagents,
        )

    private fun classifyEmbeddingService(): String =
        when (embeddingService) {
            is OnnxEmbeddingService -> "onnx"
            else -> "ollama"
        }

    private fun isRunningInDocker(): Boolean = File("/.dockerenv").exists()

    private suspend fun checkDatabase(): Boolean =
        withContext(Dispatchers.VT) {
            try {
                driver.execute(null, "SELECT 1", 0, null)
                true
            } catch (_: java.sql.SQLException) {
                false
            } catch (_: IllegalStateException) {
                false
            }
        }

    private suspend fun countMemoryFacts(): Int =
        withContext(Dispatchers.VT) {
            try {
                var count = 0
                driver.executeQuery(
                    null,
                    "SELECT count(*) FROM memory_facts",
                    { cursor ->
                        if (cursor.next().value) {
                            count = cursor.getLong(0)?.toInt() ?: 0
                        }
                        app.cash.sqldelight.db.QueryResult
                            .Value(Unit)
                    },
                    0,
                )
                count
            } catch (_: java.sql.SQLException) {
                0
            } catch (_: IllegalStateException) {
                0
            }
        }
}
