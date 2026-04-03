package io.github.klaw.engine.socket

import io.github.klaw.common.config.AgentConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.engine.agent.AgentContext
import io.github.klaw.engine.agent.AgentRegistry
import io.github.klaw.engine.agent.AgentServices
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.init.InitCliHandler
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.llm.LlmUsageTracker
import io.github.klaw.engine.maintenance.ReindexService
import io.github.klaw.engine.memory.DailyConsolidationService
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.tools.DoctorDeepProbe
import io.github.klaw.engine.tools.EngineHealthProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class CliCommandDispatcherAgentRoutingTest {
    private val defaultSessionManager = mockk<SessionManager>(relaxed = true)
    private val defaultMemoryService = mockk<MemoryService>(relaxed = true)
    private val defaultScheduler = mockk<KlawScheduler>(relaxed = true)

    private fun createDispatcher(agentRegistry: AgentRegistry = AgentRegistry()): CliCommandDispatcher =
        CliCommandDispatcher(
            initCliHandler = mockk(relaxed = true),
            sessionManager = defaultSessionManager,
            klawScheduler = defaultScheduler,
            memoryService = defaultMemoryService,
            reindexService = mockk(relaxed = true),
            skillRegistry = mockk(relaxed = true),
            consolidationService = mockk(relaxed = true),
            engineHealthProvider = mockk(relaxed = true),
            llmUsageTracker = mockk(relaxed = true),
            llmRouter = mockk(relaxed = true),
            config = mockk(relaxed = true),
            doctorDeepProbe = mockk(relaxed = true),
            commandsCliHandler = mockk(relaxed = true),
            contextDiagnoseHandler = ContextDiagnoseHandler(mockk(relaxed = true), mockk(relaxed = true)),
            agentRegistry = agentRegistry,
        )

    @Test
    fun `dispatch uses agent-specific sessionManager for sessions command`() =
        runTest {
            val agentRegistry = AgentRegistry()
            val agentSessionManager = mockk<SessionManager>(relaxed = true)
            coEvery { agentSessionManager.listSessions() } returns emptyList()

            val ctx =
                AgentContext(
                    agentId = "agent-X",
                    agentConfig = AgentConfig(workspace = "/tmp/x"),
                    services = AgentServices(sessionManager = agentSessionManager),
                )
            agentRegistry.register("agent-X", ctx)

            val dispatcher = createDispatcher(agentRegistry)
            dispatcher.dispatch(CliRequestMessage(command = "sessions", agentId = "agent-X"))

            coVerify { agentSessionManager.listSessions() }
            coVerify(exactly = 0) { defaultSessionManager.listSessions() }
        }

    @Test
    fun `dispatch uses agent-specific memoryService for memory_search`() =
        runTest {
            val agentRegistry = AgentRegistry()
            val agentMemory = mockk<MemoryService>(relaxed = true)
            coEvery { agentMemory.search(any(), any(), any()) } returns """{"results":[]}"""

            val ctx =
                AgentContext(
                    agentId = "agent-Y",
                    agentConfig = AgentConfig(workspace = "/tmp/y"),
                    services = AgentServices(memoryService = agentMemory),
                )
            agentRegistry.register("agent-Y", ctx)

            val dispatcher = createDispatcher(agentRegistry)
            dispatcher.dispatch(
                CliRequestMessage(
                    command = "memory_search",
                    params = mapOf("query" to "test"),
                    agentId = "agent-Y",
                ),
            )

            coVerify { agentMemory.search("test", any(), any()) }
            coVerify(exactly = 0) { defaultMemoryService.search(any(), any(), any()) }
        }

    @Test
    fun `dispatch falls back to singleton when agent not registered`() =
        runTest {
            val agentRegistry = AgentRegistry()
            coEvery { defaultSessionManager.listSessions() } returns emptyList()

            val dispatcher = createDispatcher(agentRegistry)
            dispatcher.dispatch(CliRequestMessage(command = "sessions", agentId = "default"))

            coVerify { defaultSessionManager.listSessions() }
        }

    @Test
    fun `dispatch with unknown agentId falls back to singleton`() =
        runTest {
            val agentRegistry = AgentRegistry()
            coEvery { defaultSessionManager.listSessions() } returns emptyList()

            val dispatcher = createDispatcher(agentRegistry)
            dispatcher.dispatch(CliRequestMessage(command = "sessions", agentId = "nonexistent"))

            coVerify { defaultSessionManager.listSessions() }
        }
}
