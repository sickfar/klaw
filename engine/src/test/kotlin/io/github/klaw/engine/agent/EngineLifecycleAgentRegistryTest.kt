package io.github.klaw.engine.agent

import io.github.klaw.common.config.AgentConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.engine.db.NoOpSqliteVecLoader
import io.github.klaw.engine.fixtures.testEngineConfig
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.tools.ActiveSubagentJobs
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class EngineLifecycleAgentRegistryTest {
    @TempDir
    lateinit var tempDir: Path

    private fun createConfig(agents: Map<String, AgentConfig>): EngineConfig {
        val base = testEngineConfig()
        return EngineConfig(
            providers = base.providers,
            models = base.models,
            routing = base.routing,
            processing = base.processing,
            memory = base.memory,
            context = base.context,
            codeExecution = base.codeExecution,
            files = base.files,
            httpRetry = base.httpRetry,
            logging = base.logging,
            agents = agents,
        )
    }

    private fun createShared(config: EngineConfig): SharedServices {
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
        )
    }

    @Test
    fun `initializeAgents registers enabled agents`() {
        val ws1 = tempDir.resolve("ws1").toFile().also { it.mkdirs() }
        val ws2 = tempDir.resolve("ws2").toFile().also { it.mkdirs() }
        val agents =
            mapOf(
                "agent1" to AgentConfig(workspace = ws1.absolutePath),
                "agent2" to AgentConfig(workspace = ws2.absolutePath),
            )
        val config = createConfig(agents)
        val shared = createShared(config)
        val factory = AgentContextFactory(shared)
        val registry = AgentRegistry()

        initializeAgents(
            config = config,
            factory = factory,
            registry = registry,
            dirs =
                AgentDirectories(
                    stateDir = tempDir.resolve("state").toString(),
                    dataDir = tempDir.resolve("data").toString(),
                    configDir = tempDir.resolve("config").toString(),
                    conversationsDir = tempDir.resolve("conversations").toString(),
                ),
        )

        assertEquals(2, registry.all().size)
        assertNotNull(registry.get("agent1"))
        assertNotNull(registry.get("agent2"))

        registry.shutdown()
    }

    @Test
    fun `initializeAgents skips disabled agents`() {
        val ws1 = tempDir.resolve("ws1").toFile().also { it.mkdirs() }
        val ws2 = tempDir.resolve("ws2").toFile().also { it.mkdirs() }
        val agents =
            mapOf(
                "enabled" to AgentConfig(workspace = ws1.absolutePath, enabled = true),
                "disabled" to AgentConfig(workspace = ws2.absolutePath, enabled = false),
            )
        val config = createConfig(agents)
        val shared = createShared(config)
        val factory = AgentContextFactory(shared)
        val registry = AgentRegistry()

        initializeAgents(
            config = config,
            factory = factory,
            registry = registry,
            dirs =
                AgentDirectories(
                    stateDir = tempDir.resolve("state").toString(),
                    dataDir = tempDir.resolve("data").toString(),
                    configDir = tempDir.resolve("config").toString(),
                    conversationsDir = tempDir.resolve("conversations").toString(),
                ),
        )

        assertEquals(1, registry.all().size)
        assertNotNull(registry.get("enabled"))
        assertNull(registry.getOrNull("disabled"))

        registry.shutdown()
    }

    @Test
    fun `shutdown stops all agents`() {
        val ws = tempDir.resolve("ws").toFile().also { it.mkdirs() }
        val agents = mapOf("a" to AgentConfig(workspace = ws.absolutePath))
        val config = createConfig(agents)
        val shared = createShared(config)
        val factory = AgentContextFactory(shared)
        val registry = AgentRegistry()

        initializeAgents(
            config = config,
            factory = factory,
            registry = registry,
            dirs =
                AgentDirectories(
                    stateDir = tempDir.resolve("state").toString(),
                    dataDir = tempDir.resolve("data").toString(),
                    configDir = tempDir.resolve("config").toString(),
                    conversationsDir = tempDir.resolve("conversations").toString(),
                ),
        )

        assertEquals(1, registry.all().size)
        registry.shutdown()
        assertTrue(registry.all().isEmpty())
    }
}
