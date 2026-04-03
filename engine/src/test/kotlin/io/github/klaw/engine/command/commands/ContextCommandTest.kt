package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.ContextDiagnosticsBreakdown
import io.github.klaw.engine.context.ContextResult
import io.github.klaw.engine.session.Session
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class ContextCommandTest {
    private val contextBuilder = mockk<ContextBuilder>()
    private val command = ContextCommand(contextBuilder)

    private fun commandMsg(
        command: String = "context",
        chatId: String = "c1",
        channel: String = "telegram",
    ) = CommandSocketMessage(channel = channel, chatId = chatId, command = command)

    private fun session(
        chatId: String = "c1",
        model: String = "test/model",
        segmentStart: String = "2026-04-01T10:00:00Z",
    ) = Session(
        chatId = chatId,
        model = model,
        segmentStart = segmentStart,
        createdAt = Clock.System.now(),
    )

    private fun testDiagnostics() =
        ContextDiagnosticsBreakdown(
            systemPromptTokens = 3200,
            systemPromptChars = 12800,
            summaryTokens = 1500,
            summaryChars = 6000,
            pendingTokens = 5,
            pendingChars = 25,
            toolTokens = 4200,
            toolChars = 16800,
            toolCount = 18,
            overhead = 8905,
            messageBudget = 91095,
            windowMessageCount = 42,
            windowMessageTokens = 18500L,
            windowMessageChars = 74000L,
            firstMessageTime = "2026-03-30T14:05:00Z",
            lastMessageTime = "2026-03-31T10:30:00Z",
            summaryCount = 3,
            hasEvictedSummaries = true,
            coverageEnd = "2026-03-30T18:00:00Z",
            autoRagEnabled = true,
            autoRagTriggered = true,
            autoRagResultCount = 3,
            compactionEnabled = true,
            compactionThreshold = 100000,
            compactionWouldTrigger = false,
            skillCount = 4,
            inlineSkills = true,
            toolNames = listOf("memory_search", "memory_save", "file_read"),
            windowTokenCharRatio = 4.0,
        )

    private fun stubContextBuilder(session: Session) {
        coEvery {
            contextBuilder.buildContext(
                session = session,
                pendingMessages = listOf("(diagnostic simulation)"),
                isSubagent = false,
                includeDiagnostics = true,
            )
        } returns
            ContextResult(
                messages = emptyList(),
                tools = emptyList(),
                uncoveredMessageTokens = 0L,
                budget = 100000,
                diagnostics = testDiagnostics(),
            )
    }

    @Test
    fun `command name and description`() {
        assertEquals("context", command.name)
        assertTrue(command.description.isNotEmpty())
    }

    @Test
    fun `output contains all major sections`() =
        runTest {
            val s = session()
            stubContextBuilder(s)

            val result = command.handle(commandMsg(), s)

            assertTrue(result.contains("Context Diagnostics"), "header")
            assertTrue(result.contains("Session"), "Session section")
            assertTrue(result.contains("Budget Breakdown"), "Budget section")
            assertTrue(result.contains("Message Window"), "Message Window section")
            assertTrue(result.contains("Summaries"), "Summaries section")
            assertTrue(result.contains("Auto-RAG"), "Auto-RAG section")
            assertTrue(result.contains("Tools"), "Tools section")
            assertTrue(result.contains("Skills"), "Skills section")
            assertTrue(result.contains("Compaction"), "Compaction section")
        }

    @Test
    fun `output includes session details`() =
        runTest {
            val s = session(chatId = "telegram_123", model = "deepseek/chat", segmentStart = "2026-04-01T12:00:00Z")
            stubContextBuilder(s)

            val result = command.handle(commandMsg(chatId = "telegram_123"), s)

            assertTrue(result.contains("telegram_123"), "chatId")
            assertTrue(result.contains("deepseek/chat"), "model")
            assertTrue(result.contains("2026-04-01T12:00:00Z"), "segmentStart")
        }

    @Test
    fun `output includes diagnostic numbers`() =
        runTest {
            val s = session()
            stubContextBuilder(s)

            val result = command.handle(commandMsg(), s)

            assertTrue(result.contains("3200"), "systemPromptTokens")
            assertTrue(result.contains("18500"), "windowMessageTokens")
            assertTrue(result.contains("100000"), "budget or compactionThreshold")
            assertTrue(result.contains("memory_search"), "tool name")
        }

    @Test
    fun `output does not contain escaped newlines`() =
        runTest {
            val s = session()
            stubContextBuilder(s)

            val result = command.handle(commandMsg(), s)

            assertTrue(result.contains("\n"), "should contain real newlines")
        }

    @Test
    fun `returns fallback when diagnostics unavailable`() =
        runTest {
            val s = session()
            coEvery {
                contextBuilder.buildContext(
                    session = s,
                    pendingMessages = listOf("(diagnostic simulation)"),
                    isSubagent = false,
                    includeDiagnostics = true,
                )
            } returns
                ContextResult(
                    messages = emptyList(),
                    tools = emptyList(),
                    diagnostics = null,
                )

            val result = command.handle(commandMsg(), s)

            assertEquals("Diagnostics unavailable", result)
        }
}
