package io.github.klaw.engine.message

import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.util.approximateTokenCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolCallLoopRunnerTest {
    @Test
    fun `approximateMessageTokens counts tool call arguments`() {
        val arguments = "some long arguments string"
        val msg =
            LlmMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(ToolCall(id = "call_1", name = "file_write", arguments = arguments)),
            )
        val result = ToolCallLoopRunner.approximateMessageTokens(msg)
        val expected =
            approximateTokenCount("call_1") +
                approximateTokenCount("file_write") +
                approximateTokenCount(arguments)
        assertTrue(result > 0, "Expected non-zero token count for tool call message, got $result")
        assertEquals(expected, result)
    }

    @Test
    fun `approximateMessageTokens handles plain text message`() {
        val content = "hello world"
        val msg =
            LlmMessage(
                role = "user",
                content = content,
                toolCalls = null,
            )
        val result = ToolCallLoopRunner.approximateMessageTokens(msg)
        assertEquals(approximateTokenCount(content), result)
    }

    @Test
    fun `approximateMessageTokens includes toolCallId`() {
        val content = "result"
        val toolCallId = "call_1"
        val msg =
            LlmMessage(
                role = "tool",
                content = content,
                toolCallId = toolCallId,
            )
        val result = ToolCallLoopRunner.approximateMessageTokens(msg)
        val expected = approximateTokenCount(content) + approximateTokenCount(toolCallId)
        assertEquals(expected, result)
    }
}
