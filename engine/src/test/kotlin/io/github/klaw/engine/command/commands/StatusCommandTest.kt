package io.github.klaw.engine.command.commands

import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.FilesConfig
import io.github.klaw.common.config.HttpRetryConfig
import io.github.klaw.common.config.LoggingConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ModelConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.context.SummaryRepository
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class StatusCommandTest {
    private fun commandMsg(
        command: String,
        args: String? = null,
        chatId: String = "c1",
        channel: String = "telegram",
    ) = CommandSocketMessage(channel = channel, chatId = chatId, command = command, args = args)

    private fun session(
        chatId: String = "c1",
        model: String = "test/model",
    ) = Session(
        chatId = chatId,
        model = model,
        segmentStart = Clock.System.now().toString(),
        createdAt = Clock.System.now(),
    )

    private fun makeConfig(tokenBudget: Int = 4096) =
        EngineConfig(
            providers = mapOf("test" to ProviderConfig(type = "openai-compatible", endpoint = "http://localhost")),
            models = mapOf("test/model" to ModelConfig()),
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
            context = ContextConfig(tokenBudget = tokenBudget, subagentHistory = 5),
            processing = ProcessingConfig(debounceMs = 100, maxConcurrentLlm = 2, maxToolCallRounds = 5),
            httpRetry =
                HttpRetryConfig(
                    maxRetries = 1,
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
                    keepAliveMaxExecutions = 100,
                ),
            files = FilesConfig(maxFileSizeBytes = 10485760L),
        )

    @Test
    fun `shows chat, model, and segment in output`() =
        runTest {
            val msgRepo =
                mockk<MessageRepository> {
                    coEvery { getWindowStats(any(), any(), any(), any()) } returns
                        MessageRepository.WindowStats(
                            messageCount = 5,
                            totalTokens = 100,
                            firstMessageTime = null,
                            lastMessageTime = null,
                        )
                }
            val summaryRepo =
                mockk<SummaryRepository> {
                    coEvery { maxCoverageEnd(any(), any()) } returns null
                }
            val result =
                StatusCommand(makeConfig(), msgRepo, summaryRepo).handle(
                    commandMsg("status"),
                    session(chatId = "chat1", model = "glm-5"),
                )
            assertTrue(result.contains("chat1"))
            assertTrue(result.contains("glm-5"))
            assertTrue(result.contains("100"))
            assertTrue(result.contains("5 msgs"))
        }

    @Test
    fun `shows coverage end when present`() =
        runTest {
            val msgRepo =
                mockk<MessageRepository> {
                    coEvery { getWindowStats(any(), any(), any(), any()) } returns
                        MessageRepository.WindowStats(
                            messageCount = 3,
                            totalTokens = 50,
                            firstMessageTime = null,
                            lastMessageTime = null,
                        )
                }
            val summaryRepo =
                mockk<SummaryRepository> {
                    coEvery { maxCoverageEnd(any(), any()) } returns "2024-01-01T10:30:00.000Z"
                }
            val result =
                StatusCommand(makeConfig(), msgRepo, summaryRepo).handle(
                    commandMsg("status"),
                    session(),
                )
            assertTrue(result.contains("10:30:00Z"), "Expected time in output but got: $result")
        }

    @Test
    fun `percentage is zero when no tokens used`() =
        runTest {
            val msgRepo =
                mockk<MessageRepository> {
                    coEvery { getWindowStats(any(), any(), any(), any()) } returns
                        MessageRepository.WindowStats(
                            messageCount = 0,
                            totalTokens = 0,
                            firstMessageTime = null,
                            lastMessageTime = null,
                        )
                }
            val summaryRepo =
                mockk<SummaryRepository> {
                    coEvery { maxCoverageEnd(any(), any()) } returns null
                }
            val result =
                StatusCommand(makeConfig(tokenBudget = 4096), msgRepo, summaryRepo).handle(
                    commandMsg("status"),
                    session(),
                )
            assertTrue(result.contains("0%"))
        }
}
