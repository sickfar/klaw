package io.github.klaw.engine.tools

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.DocsConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.mcp.McpToolRegistry
import io.github.klaw.engine.memory.EmbeddingService
import io.github.klaw.engine.memory.OnnxEmbeddingService
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.socket.EngineOutboundBuffer
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.workspace.HeartbeatRunner
import io.github.klaw.engine.workspace.HeartbeatRunnerFactory
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import jakarta.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("LargeClass")
class EngineHealthProviderTest {
    private val socketServer = mockk<EngineSocketServer>()
    private val outboundBuffer = mockk<EngineOutboundBuffer>()
    private val scheduler = mockk<KlawScheduler>()
    private val sessionManager = mockk<SessionManager>()
    private val sandboxManager = mockk<SandboxManager>()
    private val mcpToolRegistry = McpToolRegistry()
    private val embeddingService = mockk<EmbeddingService>()
    private val sqliteVecLoader = mockk<SqliteVecLoader>()
    private val heartbeatRunnerFactory = mockk<HeartbeatRunnerFactory>()

    private val driver =
        JdbcSqliteDriver("jdbc:sqlite:").also {
            KlawDatabase.Schema.create(it)
        }

    private fun buildConfig(
        docsEnabled: Boolean = true,
        keepAlive: Boolean = false,
    ): EngineConfig =
        EngineConfig(
            providers = emptyMap(),
            models = emptyMap(),
            routing =
                RoutingConfig(
                    default = "test/model",
                    fallback = emptyList(),
                    tasks = TaskRoutingConfig(summarization = "test/model", subagent = "test/model"),
                ),
            memory =
                MemoryConfig(
                    embedding = EmbeddingConfig(type = "onnx", model = "test"),
                    chunking = ChunkingConfig(size = 100, overlap = 10),
                    search = SearchConfig(topK = 5),
                ),
            context = ContextConfig(subagentHistory = 3),
            processing = ProcessingConfig(debounceMs = 100, maxConcurrentLlm = 1, maxToolCallRounds = 5),
            codeExecution = CodeExecutionConfig(keepAlive = keepAlive),
            docs = DocsConfig(enabled = docsEnabled),
        )

    private fun buildInfra(mcpReg: McpToolRegistry = mcpToolRegistry): HealthInfrastructure =
        HealthInfrastructure(
            socketServerProvider = Provider { socketServer },
            outboundBuffer = outboundBuffer,
            scheduler = scheduler,
            sessionManager = sessionManager,
            sandboxManager = sandboxManager,
            mcpToolRegistry = mcpReg,
        )

    private fun buildProvider(config: EngineConfig = buildConfig()): EngineHealthProvider =
        EngineHealthProvider(
            infra = buildInfra(),
            embeddingService = embeddingService,
            sqliteVecLoader = sqliteVecLoader,
            driver = driver,
            heartbeatRunnerFactoryProvider = Provider { heartbeatRunnerFactory },
            config = config,
        )

    private fun stubDefaults() {
        every { socketServer.isGatewayConnected } returns false
        coEvery { scheduler.jobCount() } returns 0
        coEvery { sessionManager.listSessions() } returns emptyList()
        every { sandboxManager.isKeepAlive } returns false
        every { sandboxManager.isContainerActive } returns false
        every { sandboxManager.currentExecutionCount } returns 0
        every { sqliteVecLoader.isAvailable() } returns false
        every { outboundBuffer.pendingCount() } returns 0
        every { heartbeatRunnerFactory.runner } returns null
    }

    @Test
    fun `getHealth with connected gateway returns connected status`() =
        runTest {
            stubDefaults()
            every { socketServer.isGatewayConnected } returns true
            val provider = buildProvider()
            provider.markStarted()
            val health = provider.getHealth()
            assertEquals("connected", health.gatewayStatus)
        }

    @Test
    fun `getHealth with disconnected gateway returns disconnected status`() =
        runTest {
            stubDefaults()
            every { socketServer.isGatewayConnected } returns false
            val provider = buildProvider()
            provider.markStarted()
            val health = provider.getHealth()
            assertEquals("disconnected", health.gatewayStatus)
        }

    @Test
    fun `uptime is positive after markStarted`() =
        runTest {
            stubDefaults()
            val provider = buildProvider()
            provider.markStarted()
            val health = provider.getHealth()
            val uptime = java.time.Duration.parse(health.engineUptime)
            assertTrue(uptime >= java.time.Duration.ZERO)
        }

    @Test
    fun `pending deliveries propagated from buffer`() =
        runTest {
            stubDefaults()
            every { outboundBuffer.pendingCount() } returns 42
            val provider = buildProvider()
            provider.markStarted()
            val health = provider.getHealth()
            assertEquals(42, health.pendingDeliveries)
        }

    @Test
    fun `scheduled jobs count propagated from scheduler`() =
        runTest {
            stubDefaults()
            coEvery { scheduler.jobCount() } returns 7
            val provider = buildProvider()
            provider.markStarted()
            val health = provider.getHealth()
            assertEquals(7, health.scheduledJobs)
        }

    @Test
    fun `active sessions count propagated from session manager`() =
        runTest {
            stubDefaults()
            val sessions =
                listOf(
                    mockk<Session>(),
                    mockk<Session>(),
                    mockk<Session>(),
                )
            coEvery { sessionManager.listSessions() } returns sessions
            val provider = buildProvider()
            provider.markStarted()
            val health = provider.getHealth()
            assertEquals(3, health.activeSessions)
        }

    @Test
    fun `sandbox keepAlive enabled and container active`() =
        runTest {
            stubDefaults()
            every { sandboxManager.isKeepAlive } returns true
            every { sandboxManager.isContainerActive } returns true
            every { sandboxManager.currentExecutionCount } returns 5
            val provider = buildProvider(buildConfig(keepAlive = true))
            provider.markStarted()
            val health = provider.getHealth()
            assertTrue(health.sandbox.keepAlive)
            assertTrue(health.sandbox.containerActive)
            assertEquals(5, health.sandbox.executions)
        }

    @Test
    fun `sandbox keepAlive disabled`() =
        runTest {
            stubDefaults()
            every { sandboxManager.isKeepAlive } returns false
            every { sandboxManager.isContainerActive } returns false
            every { sandboxManager.currentExecutionCount } returns 0
            val provider = buildProvider()
            provider.markStarted()
            val health = provider.getHealth()
            assertFalse(health.sandbox.keepAlive)
            assertFalse(health.sandbox.containerActive)
            assertEquals(0, health.sandbox.executions)
        }

    @Test
    fun `mcp servers list propagated`() =
        runTest {
            stubDefaults()
            val registry = McpToolRegistry()
            registry.registerClient("server-a", mockk())
            registry.registerClient("server-b", mockk())
            val provider =
                EngineHealthProvider(
                    infra = buildInfra(mcpReg = registry),
                    embeddingService = embeddingService,
                    sqliteVecLoader = sqliteVecLoader,
                    driver = driver,
                    heartbeatRunnerFactoryProvider = Provider { heartbeatRunnerFactory },
                    config = buildConfig(),
                )
            provider.markStarted()
            val health = provider.getHealth()
            assertTrue(health.mcpServers.containsAll(listOf("server-a", "server-b")))
            assertEquals(2, health.mcpServers.size)
        }

    @Test
    fun `embedding service onnx detected`() =
        runTest {
            stubDefaults()
            val onnx = mockk<OnnxEmbeddingService>()
            val provider =
                EngineHealthProvider(
                    infra = buildInfra(),
                    embeddingService = onnx,
                    sqliteVecLoader = sqliteVecLoader,
                    driver = driver,
                    heartbeatRunnerFactoryProvider = Provider { heartbeatRunnerFactory },
                    config = buildConfig(),
                )
            provider.markStarted()
            val health = provider.getHealth()
            assertEquals("onnx", health.embeddingService)
        }

    @Test
    fun `embedding service ollama detected`() =
        runTest {
            stubDefaults()
            val ollama = mockk<EmbeddingService>()
            val provider =
                EngineHealthProvider(
                    infra = buildInfra(),
                    embeddingService = ollama,
                    sqliteVecLoader = sqliteVecLoader,
                    driver = driver,
                    heartbeatRunnerFactoryProvider = Provider { heartbeatRunnerFactory },
                    config = buildConfig(),
                )
            provider.markStarted()
            val health = provider.getHealth()
            assertEquals("ollama", health.embeddingService)
        }

    @Test
    fun `sqlite vec availability propagated`() =
        runTest {
            stubDefaults()
            every { sqliteVecLoader.isAvailable() } returns true
            val provider = buildProvider()
            provider.markStarted()
            val health = provider.getHealth()
            assertTrue(health.sqliteVec)
        }

    @Test
    fun `database ok when SELECT 1 succeeds`() =
        runTest {
            stubDefaults()
            val provider = buildProvider()
            provider.markStarted()
            val health = provider.getHealth()
            assertTrue(health.databaseOk)
        }

    @Test
    fun `heartbeat running state propagated`() =
        runTest {
            stubDefaults()
            val heartbeatRunner = mockk<HeartbeatRunner>()
            every { heartbeatRunner.isRunning } returns true
            every { heartbeatRunnerFactory.runner } returns heartbeatRunner
            val provider = buildProvider()
            provider.markStarted()
            val health = provider.getHealth()
            assertTrue(health.heartbeatRunning)
        }

    @Test
    fun `heartbeat null returns false`() =
        runTest {
            stubDefaults()
            every { heartbeatRunnerFactory.runner } returns null
            val provider = buildProvider()
            provider.markStarted()
            val health = provider.getHealth()
            assertFalse(health.heartbeatRunning)
        }

    @Test
    fun `docs enabled from config`() =
        runTest {
            stubDefaults()
            val provider = buildProvider(buildConfig(docsEnabled = true))
            provider.markStarted()
            val health = provider.getHealth()
            assertTrue(health.docsEnabled)
        }

    @Test
    fun `docs disabled from config`() =
        runTest {
            stubDefaults()
            val provider = buildProvider(buildConfig(docsEnabled = false))
            provider.markStarted()
            val health = provider.getHealth()
            assertFalse(health.docsEnabled)
        }

    @Test
    fun `memory chunks count from database`() =
        runTest {
            stubDefaults()
            val provider = buildProvider()
            provider.markStarted()
            val health = provider.getHealth()
            assertEquals(0, health.memoryChunks)
        }

    @Test
    fun `getContextStatus returns gateway connected state`() =
        runTest {
            every { socketServer.isGatewayConnected } returns true
            every { sandboxManager.isContainerActive } returns false
            every { sandboxManager.isKeepAlive } returns false
            coEvery { scheduler.jobCount() } returns 0
            coEvery { sessionManager.listSessions() } returns emptyList()
            val provider = buildProvider()
            provider.markStarted()
            val status = provider.getContextStatus()
            assertTrue(status.gatewayConnected)
        }

    @Test
    fun `getContextStatus sandbox ready when keepAlive disabled`() =
        runTest {
            every { socketServer.isGatewayConnected } returns false
            every { sandboxManager.isContainerActive } returns false
            every { sandboxManager.isKeepAlive } returns false
            coEvery { scheduler.jobCount() } returns 0
            coEvery { sessionManager.listSessions() } returns emptyList()
            val provider = buildProvider()
            provider.markStarted()
            val status = provider.getContextStatus()
            assertTrue(status.sandboxReady)
        }

    @Test
    fun `getContextStatus sandbox not ready when keepAlive enabled but container inactive`() =
        runTest {
            every { socketServer.isGatewayConnected } returns false
            every { sandboxManager.isContainerActive } returns false
            every { sandboxManager.isKeepAlive } returns true
            coEvery { scheduler.jobCount() } returns 0
            coEvery { sessionManager.listSessions() } returns emptyList()
            val provider = buildProvider()
            provider.markStarted()
            val status = provider.getContextStatus()
            assertFalse(status.sandboxReady)
        }
}
