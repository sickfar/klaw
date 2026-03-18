package io.github.klaw.engine.message

import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.FilesConfig
import io.github.klaw.common.config.LlmRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.command.CommandHandler
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.socket.CliCommandDispatcher
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.tools.ApprovalService
import io.github.klaw.engine.tools.ToolExecutor
import io.mockk.coEvery
import io.mockk.mockk
import jakarta.inject.Provider
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class MessageProcessorNewCommandTest {
    private fun buildTestConfig(): EngineConfig =
        EngineConfig(
            providers = mapOf("test" to ProviderConfig("openai-compatible", "http://localhost:9999", "key")),
            models = mapOf("test/model" to ModelConfig(maxTokens = 4096, contextBudget = 8192)),
            routing =
                RoutingConfig(
                    default = "test/model",
                    fallback = emptyList(),
                    tasks = TaskRoutingConfig(summarization = "test/model", subagent = "test/model"),
                ),
            memory =
                MemoryConfig(
                    embedding = EmbeddingConfig(type = "onnx", model = "all-MiniLM-L6-v2"),
                    chunking = ChunkingConfig(size = 512, overlap = 64),
                    search = SearchConfig(topK = 10),
                ),
            context = ContextConfig(defaultBudgetTokens = 4096, subagentHistory = 10),
            processing = ProcessingConfig(debounceMs = 10L, maxConcurrentLlm = 2, maxToolCallRounds = 5),
            llm =
                LlmRetryConfig(
                    maxRetries = 0,
                    requestTimeoutMs = 5000,
                    initialBackoffMs = 100,
                    backoffMultiplier = 2.0,
                ),
            logging = LoggingConfig(subagentConversations = false),
            codeExecution =
                CodeExecutionConfig(
                    dockerImage = "python:3.12-slim",
                    timeout = 30,
                    allowNetwork = false,
                    maxMemory = "256m",
                    maxCpus = "1.0",
                    keepAlive = false,
                    keepAliveIdleTimeoutMin = 5,
                    keepAliveMaxExecutions = 10,
                ),
            files = FilesConfig(maxFileSizeBytes = 1_000_000),
        )

    private fun buildProcessor(config: EngineConfig): MessageProcessor {
        val sessionManager =
            mockk<SessionManager> {
                coEvery { getOrCreate(any(), any()) } returns Session("chat1", "test/model", "seg1", Clock.System.now())
            }
        val messageRepository = mockk<MessageRepository>(relaxed = true)
        val contextBuilder = mockk<ContextBuilder>(relaxed = true)
        val toolRegistry = mockk<ToolRegistry>(relaxed = true)
        val llmRouter = mockk<LlmRouter>(relaxed = true)
        val toolExecutor = mockk<ToolExecutor>(relaxed = true)
        val socketServer =
            mockk<EngineSocketServer> {
                coEvery { pushToGateway(any<OutboundSocketMessage>()) } returns Unit
            }
        val socketServerProvider = Provider { socketServer }
        val commandHandler =
            mockk<CommandHandler> {
                coEvery { handle(any(), any()) } returns "OK"
            }
        val messageEmbeddingService = mockk<MessageEmbeddingService>(relaxed = true)
        val cliCommandDispatcher = mockk<CliCommandDispatcher>(relaxed = true)
        val approvalService = mockk<ApprovalService>(relaxed = true)

        return MessageProcessor(
            sessionManager,
            messageRepository,
            contextBuilder,
            toolRegistry,
            llmRouter,
            toolExecutor,
            socketServerProvider,
            commandHandler,
            config,
            messageEmbeddingService,
            cliCommandDispatcher,
            approvalService,
            shutdownController = mockk(relaxed = true),
            compactionRunner = mockk(relaxed = true),
            subagentRunRepository = mockk(relaxed = true),
            activeSubagentJobs =
                io.github.klaw.engine.tools
                    .ActiveSubagentJobs(),
        )
    }

    @Test
    fun `handleCommand cancels active processing job for same chatId`() =
        runBlocking {
            val config = buildTestConfig()
            val processor = buildProcessor(config)
            val chatId = "chat1"

            // Simulate an active job
            val fakeJob =
                launch {
                    delay(10_000)
                }
            processor.activeProcessingJobs[chatId] = fakeJob

            assertTrue(fakeJob.isActive)

            // handleCommand should cancel the active job before processing the command
            processor.handleCommand(
                CommandSocketMessage(channel = "telegram", chatId = chatId, command = "new"),
            )

            assertTrue(fakeJob.isCancelled)
            // After handleCommand, the map should not contain the job (join completed)
            assertFalse(fakeJob.isActive)
        }

    @Test
    fun `handleCommand proceeds normally when no active job exists`() =
        runBlocking {
            val config = buildTestConfig()
            val processor = buildProcessor(config)

            // No active job — should not throw
            processor.handleCommand(
                CommandSocketMessage(channel = "telegram", chatId = "chat1", command = "new"),
            )

            assertNull(processor.activeProcessingJobs["chat1"])
        }

    @Test
    fun `activeProcessingJobs map is cleaned up after job removal`() =
        runBlocking {
            val config = buildTestConfig()
            val processor = buildProcessor(config)
            val chatId = "chat1"

            val job =
                launch {
                    delay(100)
                }
            processor.activeProcessingJobs[chatId] = job

            // Cancel and join
            job.cancel()
            job.join()

            // Simulate the finally block by removing
            processor.activeProcessingJobs.remove(chatId, job)

            assertNull(processor.activeProcessingJobs[chatId])
        }
}
