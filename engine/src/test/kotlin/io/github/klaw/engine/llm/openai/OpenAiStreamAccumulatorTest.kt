package io.github.klaw.engine.llm.openai

import io.github.klaw.common.llm.FinishReason
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OpenAiStreamAccumulatorTest {
    @Test
    fun `accumulate multiple content deltas`() {
        val acc = OpenAiStreamAccumulator()
        acc.accept(contentChunk("Hello"))
        acc.accept(contentChunk(" "))
        acc.accept(contentChunk("world"))

        assertEquals("Hello world", acc.currentContent())
    }

    @Test
    fun `accumulate tool call argument fragments across chunks`() {
        val acc = OpenAiStreamAccumulator()
        acc.accept(
            toolCallChunk(index = 0, id = "call_abc", name = "get_weather", arguments = "{\"lo"),
        )
        acc.accept(toolCallChunk(index = 0, arguments = "cation\":"))
        acc.accept(toolCallChunk(index = 0, arguments = "\"Paris\"}"))

        assertTrue(acc.hasToolCalls())
        val response = acc.build()
        assertNotNull(response.toolCalls)
        assertEquals(1, response.toolCalls!!.size)
        assertEquals("call_abc", response.toolCalls!![0].id)
        assertEquals("get_weather", response.toolCalls!![0].name)
        assertEquals("{\"location\":\"Paris\"}", response.toolCalls!![0].arguments)
    }

    @Test
    fun `mixed content and tool calls`() {
        val acc = OpenAiStreamAccumulator()
        acc.accept(contentChunk("Let me check"))
        acc.accept(
            toolCallChunk(index = 0, id = "call_1", name = "search", arguments = "{\"q\":\"test\"}"),
        )

        assertTrue(acc.hasToolCalls())
        assertEquals("Let me check", acc.currentContent())

        val response = acc.build()
        assertEquals("Let me check", response.content)
        assertNotNull(response.toolCalls)
        assertEquals(1, response.toolCalls!!.size)
    }

    @Test
    fun `build returns response with finish reason stop`() {
        val acc = OpenAiStreamAccumulator()
        acc.accept(contentChunk("done"))
        acc.accept(finishChunk("stop"))

        val response = acc.build()
        assertEquals("done", response.content)
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    @Test
    fun `build returns response with finish reason tool_calls`() {
        val acc = OpenAiStreamAccumulator()
        acc.accept(toolCallChunk(index = 0, id = "call_1", name = "fn", arguments = "{}"))
        acc.accept(finishChunk("tool_calls"))

        val response = acc.build()
        assertEquals(FinishReason.TOOL_CALLS, response.finishReason)
    }

    @Test
    fun `build returns response with finish reason length`() {
        val acc = OpenAiStreamAccumulator()
        acc.accept(contentChunk("truncated"))
        acc.accept(finishChunk("length"))

        val response = acc.build()
        assertEquals(FinishReason.LENGTH, response.finishReason)
    }

    @Test
    fun `build with usage`() {
        val acc = OpenAiStreamAccumulator()
        acc.accept(contentChunk("hi"))
        acc.accept(usageChunk(promptTokens = 5, completionTokens = 2, totalTokens = 7))

        val response = acc.build()
        assertNotNull(response.usage)
        assertEquals(5, response.usage!!.promptTokens)
        assertEquals(2, response.usage!!.completionTokens)
        assertEquals(7, response.usage!!.totalTokens)
    }

    @Test
    fun `empty accumulator builds response with null content`() {
        val acc = OpenAiStreamAccumulator()
        val response = acc.build()

        assertNull(response.content)
        assertTrue(response.toolCalls.isNullOrEmpty())
        assertNull(response.usage)
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    @Test
    fun `multiple tool calls with different indices`() {
        val acc = OpenAiStreamAccumulator()
        acc.accept(toolCallChunk(index = 0, id = "call_1", name = "fn_a", arguments = "{\"a\":"))
        acc.accept(toolCallChunk(index = 1, id = "call_2", name = "fn_b", arguments = "{\"b\":"))
        acc.accept(toolCallChunk(index = 0, arguments = "1}"))
        acc.accept(toolCallChunk(index = 1, arguments = "2}"))

        val response = acc.build()
        assertNotNull(response.toolCalls)
        assertEquals(2, response.toolCalls!!.size)

        assertEquals("call_1", response.toolCalls!![0].id)
        assertEquals("fn_a", response.toolCalls!![0].name)
        assertEquals("{\"a\":1}", response.toolCalls!![0].arguments)

        assertEquals("call_2", response.toolCalls!![1].id)
        assertEquals("fn_b", response.toolCalls!![1].name)
        assertEquals("{\"b\":2}", response.toolCalls!![1].arguments)
    }

    @Test
    fun `hasToolCalls returns false when no tool calls`() {
        val acc = OpenAiStreamAccumulator()
        acc.accept(contentChunk("just text"))

        assertFalse(acc.hasToolCalls())
    }

    @Test
    fun `blank content results in null content in response`() {
        val acc = OpenAiStreamAccumulator()
        acc.accept(contentChunk(""))
        acc.accept(contentChunk(""))

        val response = acc.build()
        assertNull(response.content)
    }

    @Test
    fun `unknown finish reason defaults to STOP`() {
        val acc = OpenAiStreamAccumulator()
        acc.accept(finishChunk("content_filter"))

        val response = acc.build()
        assertEquals(FinishReason.STOP, response.finishReason)
    }

    // -- helpers --

    private fun contentChunk(text: String) =
        OpenAiStreamChunk(
            id = "chatcmpl-x",
            choices =
                listOf(
                    OpenAiStreamChoice(
                        index = 0,
                        delta = OpenAiStreamDelta(content = text),
                    ),
                ),
        )

    private fun toolCallChunk(
        index: Int,
        id: String? = null,
        name: String? = null,
        arguments: String? = null,
    ) = OpenAiStreamChunk(
        id = "chatcmpl-x",
        choices =
            listOf(
                OpenAiStreamChoice(
                    index = 0,
                    delta =
                        OpenAiStreamDelta(
                            toolCalls =
                                listOf(
                                    OpenAiStreamToolCallDelta(
                                        index = index,
                                        id = id,
                                        type = if (id != null) "function" else null,
                                        function =
                                            OpenAiStreamFunctionDelta(
                                                name = name,
                                                arguments = arguments,
                                            ),
                                    ),
                                ),
                        ),
                ),
            ),
    )

    private fun finishChunk(reason: String) =
        OpenAiStreamChunk(
            id = "chatcmpl-x",
            choices =
                listOf(
                    OpenAiStreamChoice(
                        index = 0,
                        delta = OpenAiStreamDelta(),
                        finishReason = reason,
                    ),
                ),
        )

    private fun usageChunk(
        promptTokens: Int,
        completionTokens: Int,
        totalTokens: Int,
    ) = OpenAiStreamChunk(
        id = "chatcmpl-x",
        choices = emptyList(),
        usage =
            OpenAiUsage(
                promptTokens = promptTokens,
                completionTokens = completionTokens,
                totalTokens = totalTokens,
            ),
    )
}
