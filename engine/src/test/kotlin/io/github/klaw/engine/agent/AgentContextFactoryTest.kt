package io.github.klaw.engine.agent

import io.github.klaw.common.config.AgentConfig
import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.engine.db.NoOpSqliteVecLoader
import io.github.klaw.engine.fixtures.testEngineConfig
import io.github.klaw.engine.llm.LlmRouter
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
        val llmRouter = LlmRouter(
            providers = emptyMap(),
            models = emptyMap(),
            routing = RoutingConfig(
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
            engineHealthProvider = io.mockk.mockk(relaxed = true),
        )
    }

    @Test
    fun `creates context with correct DB path`() {
        val stateDir = tempDir.resolve("state").toFile().also { it.mkdirs() }
        val workspaceDir = tempDir.resolve("workspace").toFile().also { it.mkdirs() }
        val dataDir = tempDir.resolve("data").toFile().also { it.mkdirs() }
        val configDir = tempDir.resolve("config").toFile().also { it.mkdirs() }

        val shared = createSharedServices()
        val factory = AgentContextFactory(shared)
        val agentConfig = AgentConfig(workspace = workspaceDir.absolutePath)

        val ctx = factory.create(
            agentId = "test-agent",
            agentConfig = agentConfig,
            stateDir = stateDir.absolutePath,
            dataDir = dataDir.absolutePath,
            configDir = configDir.absolutePath,
            conversationsDir = tempDir.resolve("conversations").toString(),
        )

        try {
            assertNotNull(ctx)
            val dbFile = File(stateDir, "klaw-test-agent.db")
            assertTrue(dbFile.exists(), "DB file should exist at ${dbFile.absolutePath}")
        } finally {
            ctx.shutdown()
        }
    }

    @Test
    fun `workspace auto-creation when dir does not exist`() {
        val stateDir = tempDir.resolve("state").toFile().also { it.mkdirs() }
        val workspaceDir = tempDir.resolve("new-workspace")
        val dataDir = tempDir.resolve("data").toFile().also { it.mkdirs() }
        val configDir = tempDir.resolve("config").toFile().also { it.mkdirs() }

        val shared = createSharedServices()
        val factory = AgentContextFactory(shared)
        val agentConfig = AgentConfig(workspace = workspaceDir.toString())

        val ctx = factory.create(
            agentId = "auto-ws",
            agentConfig = agentConfig,
            stateDir = stateDir.absolutePath,
            dataDir = dataDir.absolutePath,
            configDir = configDir.absolutePath,
            conversationsDir = tempDir.resolve("conversations").toString(),
        )

        try {
            assertTrue(workspaceDir.toFile().exists(), "Workspace should be auto-created")
        } finally {
            ctx.shutdown()
        }
    }

    @Test
    fun `context shutdown closes DB driver`() {
        val stateDir = tempDir.resolve("state").toFile().also { it.mkdirs() }
        val workspaceDir = tempDir.resolve("workspace").toFile().also { it.mkdirs() }
        val dataDir = tempDir.resolve("data").toFile().also { it.mkdirs() }
        val configDir = tempDir.resolve("config").toFile().also { it.mkdirs() }

        val shared = createSharedServices()
        val factory = AgentContextFactory(shared)
        val agentConfig = AgentConfig(workspace = workspaceDir.absolutePath)

        val ctx = factory.create(
            agentId = "shutdown-test",
            agentConfig = agentConfig,
            stateDir = stateDir.absolutePath,
            dataDir = dataDir.absolutePath,
            configDir = configDir.absolutePath,
            conversationsDir = tempDir.resolve("conversations").toString(),
        )

        // Should not throw
        ctx.shutdown()
        // Second shutdown should be safe (idempotent)
        ctx.shutdown()
    }
}
