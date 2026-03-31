package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.context.SummaryRepository
import io.github.klaw.engine.fixtures.testEngineConfig
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
                StatusCommand(testEngineConfig(), msgRepo, summaryRepo).handle(
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
                StatusCommand(testEngineConfig(), msgRepo, summaryRepo).handle(
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
                StatusCommand(testEngineConfig(tokenBudget = 4096), msgRepo, summaryRepo).handle(
                    commandMsg("status"),
                    session(),
                )
            assertTrue(result.contains("0%"))
        }
}
