package io.github.klaw.engine.socket

import io.github.klaw.common.config.AgentConfig
import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.engine.agent.AgentContext
import io.github.klaw.engine.agent.AgentRegistry
import io.github.klaw.engine.agent.AgentServices
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.session.SessionManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CliCommandDispatcherAgentRoutingTest {
    private fun createDispatcher(agentRegistry: AgentRegistry): CliCommandDispatcher =
        CliCommandDispatcher(
            initCliHandler = mockk(relaxed = true),
            reindexService = mockk(relaxed = true),
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
                    services =
                        AgentServices(
                            sessionManager = agentSessionManager,
                            memoryService = mockk(relaxed = true),
                            scheduler = mockk(relaxed = true),
                            skillRegistry = mockk(relaxed = true),
                        ),
                )
            agentRegistry.register("agent-X", ctx)

            val dispatcher = createDispatcher(agentRegistry)
            dispatcher.dispatch(CliRequestMessage(command = "sessions", agentId = "agent-X"))

            coVerify { agentSessionManager.listSessions() }
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
                    services =
                        AgentServices(
                            sessionManager = mockk(relaxed = true),
                            memoryService = agentMemory,
                            scheduler = mockk(relaxed = true),
                            skillRegistry = mockk(relaxed = true),
                        ),
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
        }

    @Test
    fun `dispatch returns error when agentId is unknown`() =
        runTest {
            val agentRegistry = AgentRegistry()
            val dispatcher = createDispatcher(agentRegistry)
            val result = dispatcher.dispatch(CliRequestMessage(command = "sessions", agentId = "nonexistent"))

            assertTrue(result.contains("error", ignoreCase = true))
            assertTrue(result.contains("nonexistent"))
        }
}
