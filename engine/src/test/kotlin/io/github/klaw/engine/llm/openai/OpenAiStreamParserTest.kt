package io.github.klaw.engine.llm.openai

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class OpenAiStreamParserTest {
    @Test
    fun `parse content delta`() {
        val line =
            sseData(
                """{"id":"chatcmpl-x","choices":[{"index":0,""" +
                    """"delta":{"content":"Hello"},""" +
                    """"finish_reason":null}]}""",
            )
        val chunk = OpenAiStreamParser.parseSseLine(line)

        assertNotNull(chunk)
        assertEquals("chatcmpl-x", chunk!!.id)
        assertEquals(1, chunk.choices.size)
        assertEquals("Hello", chunk.choices[0].delta.content)
        assertNull(chunk.choices[0].finishReason)
    }

    @Test
    fun `parse tool call delta`() {
        val line =
            sseData(
                """{"id":"chatcmpl-y","choices":[{"index":0,""" +
                    """"delta":{"tool_calls":[{"index":0,""" +
                    """"id":"call_abc","type":"function",""" +
                    """"function":{"name":"get_weather",""" +
                    """"arguments":"{\"lo"}}]},""" +
                    """"finish_reason":null}]}""",
            )
        val chunk = OpenAiStreamParser.parseSseLine(line)

        assertNotNull(chunk)
        val toolCalls = chunk!!.choices[0].delta.toolCalls
        assertNotNull(toolCalls)
        assertEquals(1, toolCalls!!.size)
        assertEquals(0, toolCalls[0].index)
        assertEquals("call_abc", toolCalls[0].id)
        assertEquals("function", toolCalls[0].type)
        assertEquals("get_weather", toolCalls[0].function?.name)
        assertEquals("{\"lo", toolCalls[0].function?.arguments)
    }

    @Test
    fun `parse DONE marker returns null`() {
        val result = OpenAiStreamParser.parseSseLine("data: [DONE]")
        assertNull(result)
    }

    @Test
    fun `parse finish reason stop`() {
        val line =
            sseData(
                """{"id":"x","choices":[{"index":0,""" +
                    """"delta":{},"finish_reason":"stop"}]}""",
            )
        val chunk = OpenAiStreamParser.parseSseLine(line)

        assertNotNull(chunk)
        assertEquals("stop", chunk!!.choices[0].finishReason)
    }

    @Test
    fun `parse finish reason tool_calls`() {
        val line =
            sseData(
                """{"id":"x","choices":[{"index":0,""" +
                    """"delta":{},"finish_reason":"tool_calls"}]}""",
            )
        val chunk = OpenAiStreamParser.parseSseLine(line)

        assertNotNull(chunk)
        assertEquals("tool_calls", chunk!!.choices[0].finishReason)
    }

    @Test
    fun `parse empty delta`() {
        val line =
            sseData(
                """{"id":"x","choices":[{"index":0,"delta":{}}]}""",
            )
        val chunk = OpenAiStreamParser.parseSseLine(line)

        assertNotNull(chunk)
        assertNull(chunk!!.choices[0].delta.content)
        assertNull(chunk.choices[0].delta.toolCalls)
    }

    @Test
    fun `parse usage in final chunk`() {
        val line =
            sseData(
                """{"id":"x","choices":[],"usage":{""" +
                    """"prompt_tokens":10,""" +
                    """"completion_tokens":20,""" +
                    """"total_tokens":30}}""",
            )
        val chunk = OpenAiStreamParser.parseSseLine(line)

        assertNotNull(chunk)
        val usage = chunk!!.usage
        assertNotNull(usage)
        assertEquals(10, usage!!.promptTokens)
        assertEquals(20, usage.completionTokens)
        assertEquals(30, usage.totalTokens)
    }

    @Test
    fun `ignore SSE comment line`() {
        assertNull(OpenAiStreamParser.parseSseLine(": this is a comment"))
    }

    @Test
    fun `ignore event type line`() {
        assertNull(OpenAiStreamParser.parseSseLine("event: message"))
    }

    @Test
    fun `ignore empty line`() {
        assertNull(OpenAiStreamParser.parseSseLine(""))
    }

    @Test
    fun `ignore blank line`() {
        assertNull(OpenAiStreamParser.parseSseLine("   "))
    }

    @Test
    fun `content with unicode and CJK characters`() {
        val line =
            sseData(
                """{"id":"x","choices":[{"index":0,""" +
                    """"delta":{"content":"你好世界 🌍"},""" +
                    """"finish_reason":null}]}""",
            )
        val chunk = OpenAiStreamParser.parseSseLine(line)

        assertNotNull(chunk)
        assertEquals("你好世界 🌍", chunk!!.choices[0].delta.content)
    }

    @Test
    fun `content with newlines`() {
        val line =
            sseData(
                """{"id":"x","choices":[{"index":0,""" +
                    """"delta":{"content":"line1\nline2"},""" +
                    """"finish_reason":null}]}""",
            )
        val chunk = OpenAiStreamParser.parseSseLine(line)

        assertNotNull(chunk)
        assertEquals("line1\nline2", chunk!!.choices[0].delta.content)
    }

    @Test
    fun `unknown fields are ignored`() {
        val line =
            sseData(
                """{"id":"x","object":"chat.completion.chunk",""" +
                    """"model":"gpt-4","choices":[{"index":0,""" +
                    """"delta":{"content":"hi"},"logprobs":null,""" +
                    """"finish_reason":null}]}""",
            )
        val chunk = OpenAiStreamParser.parseSseLine(line)

        assertNotNull(chunk)
        assertEquals("hi", chunk!!.choices[0].delta.content)
    }

    @Test
    fun `tool call delta with only arguments fragment`() {
        val line =
            sseData(
                """{"id":"x","choices":[{"index":0,""" +
                    """"delta":{"tool_calls":[{"index":0,""" +
                    """"function":{"arguments":"cation\"}"}}]},""" +
                    """"finish_reason":null}]}""",
            )
        val chunk = OpenAiStreamParser.parseSseLine(line)

        assertNotNull(chunk)
        val tc = chunk!!.choices[0].delta.toolCalls!![0]
        assertNull(tc.id)
        assertNull(tc.type)
        assertNull(tc.function?.name)
        assertEquals("cation\"}", tc.function?.arguments)
    }

    @Test
    fun `parse role delta`() {
        val line =
            sseData(
                """{"id":"x","choices":[{"index":0,""" +
                    """"delta":{"role":"assistant"}}]}""",
            )
        val chunk = OpenAiStreamParser.parseSseLine(line)

        assertNotNull(chunk)
        assertEquals("assistant", chunk!!.choices[0].delta.role)
        assertNull(chunk.choices[0].delta.content)
    }

    @Test
    fun `multiple tool call deltas in single chunk`() {
        val line =
            sseData(
                """{"id":"x","choices":[{"index":0,"delta":{""" +
                    """"tool_calls":[""" +
                    """{"index":0,"id":"call_1",""" +
                    """"type":"function",""" +
                    """"function":{"name":"fn1","arguments":""}},""" +
                    """{"index":1,"id":"call_2",""" +
                    """"type":"function",""" +
                    """"function":{"name":"fn2","arguments":""}}""" +
                    """]}}]}""",
            )
        val chunk = OpenAiStreamParser.parseSseLine(line)

        assertNotNull(chunk)
        val toolCalls = chunk!!.choices[0].delta.toolCalls!!
        assertEquals(2, toolCalls.size)
        assertEquals(0, toolCalls[0].index)
        assertEquals("call_1", toolCalls[0].id)
        assertEquals("fn1", toolCalls[0].function?.name)
        assertEquals(1, toolCalls[1].index)
        assertEquals("call_2", toolCalls[1].id)
        assertEquals("fn2", toolCalls[1].function?.name)
    }

    @Test
    fun `line without data prefix returns null`() {
        val line =
            """{"id":"x","choices":[{"index":0,"delta":{"content":"a"}}]}"""
        assertNull(OpenAiStreamParser.parseSseLine(line))
    }

    private fun sseData(json: String) = "data: $json"
}
