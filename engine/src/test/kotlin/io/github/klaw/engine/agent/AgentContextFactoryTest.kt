package io.github.klaw.engine.agent

import io.github.klaw.common.config.AgentConfig
import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.engine.db.NoOpSqliteVecLoader
import io.github.klaw.engine.fixtures.testEngineConfig
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.tools.ActiveSubagentJobs
import io.github.klaw.engine.scheduler.AgentKlawScheduler
import io.github.klaw.engine.tools.ToolRegistryImpl
import io.micronaut.context.ApplicationContext
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class AgentContextFactoryTest {
    @TempDir
    lateinit var tempDir: Path

    private fun createSharedServices(): SharedServices {
        val config = testEngineConfig()
        val llmRouter =
            LlmRouter(
                providers = emptyMap(),
                models = emptyMap(),
                routing =
                    RoutingConfig(
                        default = "test/model",
                        fallback = emptyList(),
                        tasks = TaskRoutingConfig("test/model", "test/model"),
                    ),
                retryConfig = HttpRetryConfig(1, 5000, 100, 2.0),
                clientFactory = null,
            )
        return SharedServices(
            llmRouter = llmRouter,
            embeddingService = StubEmbeddingService(),
            sqliteVecLoader = NoOpSqliteVecLoader(),
            globalConfig = config,
            engineHealthProvider = mockk(relaxed = true),
            dockerClient = mockk(relaxed = true),
            approvalService = mockk(relaxed = true),
            shutdownController = mockk(relaxed = true),
            scheduler = mockk(relaxed = true),
            docsService = mockk(relaxed = true),
            socketServerProvider = mockk(relaxed = true),
            processorProvider = mockk(relaxed = true),
            activeSubagentJobs = ActiveSubagentJobs(),
            webFetchTool = mockk(relaxed = true),
            webSearchTool = mockk(relaxed = true),
            applicationContext = mockk<ApplicationContext>(relaxed = true),
        )
    }

    private fun createAgentDirs(): AgentDirectories {
        val stateDir = tempDir.resolve("state").toFile().also { it.mkdirs() }
        val dataDir = tempDir.resolve("data").toFile().also { it.mkdirs() }
        val configDir = tempDir.resolve("config").toFile().also { it.mkdirs() }
        return AgentDirectories(
            stateDir = stateDir.absolutePath,
            dataDir = dataDir.absolutePath,
            configDir = configDir.absolutePath,
            conversationsDir = tempDir.resolve("conversations").toString(),
        )
    }

    @Test
    fun `creates context with correct DB path`() {
        val dirs = createAgentDirs()
        val workspaceDir = tempDir.resolve("workspace").toFile().also { it.mkdirs() }

        val shared = createSharedServices()
        val factory = AgentContextFactory(shared)
        val agentConfig = AgentConfig(workspace = workspaceDir.absolutePath)

        val ctx = factory.create(agentId = "test-agent", agentConfig = agentConfig, dirs = dirs)

        try {
            assertNotNull(ctx)
            val dbFile = File(dirs.stateDir, "klaw-test-agent.db")
            assertTrue(dbFile.exists(), "DB file should exist at ${dbFile.absolutePath}")
        } finally {
            ctx.shutdown()
        }
    }

    @Test
    fun `workspace auto-creation when dir does not exist`() {
        val dirs = createAgentDirs()
        val workspaceDir = tempDir.resolve("new-workspace")

        val shared = createSharedServices()
        val factory = AgentContextFactory(shared)
        val agentConfig = AgentConfig(workspace = workspaceDir.toString())

        val ctx = factory.create(agentId = "auto-ws", agentConfig = agentConfig, dirs = dirs)

        try {
            assertTrue(workspaceDir.toFile().exists(), "Workspace should be auto-created")
        } finally {
            ctx.shutdown()
        }
    }

    @Test
    fun `context shutdown closes DB driver`() {
        val dirs = createAgentDirs()
        val workspaceDir = tempDir.resolve("workspace").toFile().also { it.mkdirs() }

        val shared = createSharedServices()
        val factory = AgentContextFactory(shared)
        val agentConfig = AgentConfig(workspace = workspaceDir.absolutePath)

        val ctx = factory.create(agentId = "shutdown-test", agentConfig = agentConfig, dirs = dirs)

        // Should not throw
        ctx.shutdown()
        // Second shutdown should be safe (idempotent)
        ctx.shutdown()
    }

    @Test
    fun `creates context with real ToolRegistryImpl`() {
        val dirs = createAgentDirs()
        val workspaceDir = tempDir.resolve("workspace").toFile().also { it.mkdirs() }

        val shared = createSharedServices()
        val factory = AgentContextFactory(shared)
        val agentConfig = AgentConfig(workspace = workspaceDir.absolutePath)

        val ctx = factory.create(agentId = "tool-test", agentConfig = agentConfig, dirs = dirs)

        try {
            val toolRegistry = ctx.services.toolRegistry
            assertNotNull(toolRegistry, "toolRegistry should not be null")
            assertTrue(
                toolRegistry is ToolRegistryImpl,
                "toolRegistry should be ToolRegistryImpl, was ${toolRegistry!!::class.simpleName}",
            )
        } finally {
            ctx.shutdown()
        }
    }

    @Test
    fun `creates per-agent scheduler with correct DB path`() {
        val dirs = createAgentDirs()
        val workspaceDir = tempDir.resolve("workspace").toFile().also { it.mkdirs() }

        val shared = createSharedServices()
        val factory = AgentContextFactory(shared)
        val agentConfig = AgentConfig(workspace = workspaceDir.absolutePath)

        val ctx = factory.create(agentId = "sched-agent", agentConfig = agentConfig, dirs = dirs)

        try {
            val scheduler = ctx.services.scheduler
            assertNotNull(scheduler, "scheduler should not be null")
            assertTrue(
                scheduler is AgentKlawScheduler,
                "scheduler should be AgentKlawScheduler, was ${scheduler!!::class.simpleName}",
            )
            val schedulerDb = File(dirs.stateDir, "scheduler-sched-agent.db")
            assertTrue(schedulerDb.exists(), "Scheduler DB should exist at ${schedulerDb.absolutePath}")
        } finally {
            ctx.shutdown()
        }
    }

    @Test
    fun `tool registry lists tools`() {
        val dirs = createAgentDirs()
        val workspaceDir = tempDir.resolve("workspace").toFile().also { it.mkdirs() }

        val shared = createSharedServices()
        val factory = AgentContextFactory(shared)
        val agentConfig = AgentConfig(workspace = workspaceDir.absolutePath)

        val ctx = factory.create(agentId = "tool-list-test", agentConfig = agentConfig, dirs = dirs)

        try {
            val toolRegistry = ctx.services.toolRegistry as ToolRegistryImpl
            val tools = kotlinx.coroutines.runBlocking { toolRegistry.listTools() }
            assertTrue(tools.isNotEmpty(), "Tool list should not be empty")
            val toolNames = tools.map { it.name }.toSet()
            assertTrue("file_read" in toolNames, "Should have file_read tool")
            assertTrue("memory_search" in toolNames, "Should have memory_search tool")
            assertTrue("schedule_list" in toolNames, "Should have schedule_list tool")
        } finally {
            ctx.shutdown()
        }
    }
}
