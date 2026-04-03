package io.github.klaw.engine.message

import io.github.klaw.common.config.AgentConfig
import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.agent.AgentContext
import io.github.klaw.engine.agent.AgentRegistry
import io.github.klaw.engine.agent.AgentServices
import io.github.klaw.engine.command.CommandHandler
import io.github.klaw.engine.fixtures.testEngineConfig
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.socket.CliCommandDispatcher
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.tools.ActiveSubagentJobs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import jakarta.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class MessageProcessorAgentRoutingTest {
    private val config = testEngineConfig()

    private fun makeSession(
        chatId: String = "chat1",
        model: String = "test/model",
    ): Session {
        val now = Clock.System.now()
        return Session(chatId = chatId, model = model, segmentStart = now.toString(), createdAt = now)
    }

    private fun buildProcessor(
        agentRegistry: AgentRegistry,
        llmRouter: LlmRouter = mockk(relaxed = true),
        socketServerProvider: Provider<EngineSocketServer> = Provider { mockk(relaxed = true) },
        commandHandler: CommandHandler = mockk(relaxed = true),
        cliCommandDispatcher: CliCommandDispatcher = mockk(relaxed = true),
    ): MessageProcessor =
        MessageProcessor(
            llmRouter = llmRouter,
            toolExecutor = mockk(relaxed = true),
            socketServerProvider = socketServerProvider,
            commandHandler = commandHandler,
            config = config,
            cliCommandDispatcher = cliCommandDispatcher,
            approvalService = mockk(relaxed = true),
            shutdownController = mockk(relaxed = true),
            subagentRunRepository = mockk(relaxed = true),
            activeSubagentJobs = ActiveSubagentJobs(),
            agentRegistry = agentRegistry,
        )

    @Test
    fun `handleCommand uses agent-specific sessionManager when available`() =
        runTest {
            val agentRegistry = AgentRegistry()
            val agentSessionManager = mockk<SessionManager>(relaxed = true)
            val session = makeSession()
            coEvery { agentSessionManager.getOrCreate(any(), any()) } returns session

            val agentCtx =
                AgentContext(
                    agentId = "agent-A",
                    agentConfig = AgentConfig(workspace = "/tmp/a"),
                    services = AgentServices(sessionManager = agentSessionManager),
                )
            agentRegistry.register("agent-A", agentCtx)

            val commandHandler = mockk<CommandHandler>(relaxed = true)
            coEvery { commandHandler.handle(any(), any()) } returns "ok"

            val processor =
                buildProcessor(
                    agentRegistry = agentRegistry,
                    commandHandler = commandHandler,
                )

            val msg =
                CommandSocketMessage(
                    channel = "telegram",
                    chatId = "chat1",
                    command = "status",
                    agentId = "agent-A",
                )
            processor.handleCommand(msg)

            coVerify { agentSessionManager.getOrCreate("chat1", any()) }
        }

    @Test
    fun `handleCommand sends error response when agentId is unknown`() =
        runTest {
            val agentRegistry = AgentRegistry()

            val socketServer = mockk<EngineSocketServer>(relaxed = true)
            val pushed = mutableListOf<io.github.klaw.common.protocol.OutboundSocketMessage>()
            coEvery { socketServer.pushToGateway(any()) } answers { pushed.add(firstArg()) }

            val processor =
                buildProcessor(
                    agentRegistry = agentRegistry,
                    socketServerProvider = Provider { socketServer },
                )

            val msg =
                CommandSocketMessage(
                    channel = "telegram",
                    chatId = "chat1",
                    command = "status",
                    agentId = "unknown-agent",
                )
            processor.handleCommand(msg)

            assertTrue(pushed.isNotEmpty(), "Expected error response to be sent")
            assertTrue(pushed[0].content.contains("unknown-agent"), "Error should mention the agentId")
        }

    @Test
    fun `handleCliRequest dispatches to CliCommandDispatcher`() =
        runTest {
            val agentRegistry = AgentRegistry()
            val cliDispatcher = mockk<CliCommandDispatcher>(relaxed = true)
            coEvery { cliDispatcher.dispatch(any()) } returns """{"status":"ok"}"""

            val processor =
                buildProcessor(
                    agentRegistry = agentRegistry,
                    cliCommandDispatcher = cliDispatcher,
                )
            val result = processor.handleCliRequest(CliRequestMessage(command = "status", agentId = "agent-B"))

            assertEquals("""{"status":"ok"}""", result)
            coVerify { cliDispatcher.dispatch(match { it.agentId == "agent-B" }) }
        }
}
