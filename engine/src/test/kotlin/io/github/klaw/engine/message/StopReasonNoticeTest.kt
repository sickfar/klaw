package io.github.klaw.engine.message

import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class StopReasonNoticeTest {
    private fun response(
        rawFinishReason: String? = null,
        stopReason: String? = null,
        finishReason: FinishReason = FinishReason.STOP,
    ) = LlmResponse(
        content = null,
        toolCalls = null,
        usage = null,
        finishReason = finishReason,
        rawFinishReason = rawFinishReason,
        stopReason = stopReason,
    )

    @Test
    fun `normal stop produces no notice`() {
        assertNull(stopReasonNotice(response(rawFinishReason = "stop")))
    }

    @Test
    fun `end_turn produces no notice`() {
        assertNull(stopReasonNotice(response(rawFinishReason = "end_turn")))
    }

    @Test
    fun `tool_calls produces no notice`() {
        assertNull(stopReasonNotice(response(rawFinishReason = "tool_calls")))
    }

    @Test
    fun `null rawFinishReason produces no notice`() {
        assertNull(stopReasonNotice(response()))
    }

    @Test
    fun `stop_reason with finish_reason=stop produces notice`() {
        val notice = stopReasonNotice(response(rawFinishReason = "stop", stopReason = "\n"))
        assertEquals("[Response stopped: stop_reason=\n]", notice)
    }

    @Test
    fun `stop_reason with finish_reason=end_turn produces no notice`() {
        assertNull(stopReasonNotice(response(rawFinishReason = "end_turn", stopReason = "\n\nHuman:")))
    }

    @Test
    fun `length produces token limit notice`() {
        val notice = stopReasonNotice(response(rawFinishReason = "length", finishReason = FinishReason.LENGTH))
        assertEquals("[Response stopped: output token limit reached]", notice)
    }

    @Test
    fun `max_tokens produces token limit notice`() {
        val notice = stopReasonNotice(response(rawFinishReason = "max_tokens", finishReason = FinishReason.LENGTH))
        assertEquals("[Response stopped: output token limit reached]", notice)
    }

    @Test
    fun `content_filter produces filter notice`() {
        val notice = stopReasonNotice(response(rawFinishReason = "content_filter"))
        assertEquals("[Response stopped: content filter triggered]", notice)
    }

    @Test
    fun `stop_sequence with stopReason produces detailed notice`() {
        val notice =
            stopReasonNotice(
                response(rawFinishReason = "stop_sequence", stopReason = "\n\nHuman:"),
            )
        assertEquals("[Response stopped: stop sequence (\n\nHuman:)]", notice)
    }

    @Test
    fun `stop_sequence without stopReason produces basic notice`() {
        val notice = stopReasonNotice(response(rawFinishReason = "stop_sequence"))
        assertEquals("[Response stopped: stop sequence]", notice)
    }

    @Test
    fun `abort produces raw notice`() {
        val notice = stopReasonNotice(response(rawFinishReason = "abort"))
        assertEquals("[Response stopped: abort]", notice)
    }

    @Test
    fun `error produces raw notice`() {
        val notice = stopReasonNotice(response(rawFinishReason = "error"))
        assertEquals("[Response stopped: error]", notice)
    }

    @Test
    fun `unknown reason produces raw notice`() {
        val notice = stopReasonNotice(response(rawFinishReason = "network_error"))
        assertEquals("[Response stopped: network_error]", notice)
    }

    @Test
    fun `appendStopNoticeIfNeeded appends to content`() {
        val result =
            appendStopNoticeIfNeeded(
                "Hello!",
                response(rawFinishReason = "length", finishReason = FinishReason.LENGTH),
            )
        assertEquals("Hello!\n\n[Response stopped: output token limit reached]", result)
    }

    @Test
    fun `appendStopNoticeIfNeeded returns notice only when content is blank`() {
        val result =
            appendStopNoticeIfNeeded(
                "",
                response(rawFinishReason = "content_filter"),
            )
        assertEquals("[Response stopped: content filter triggered]", result)
    }

    @Test
    fun `appendStopNoticeIfNeeded returns content unchanged for normal stop`() {
        val result =
            appendStopNoticeIfNeeded(
                "Hello!",
                response(rawFinishReason = "stop"),
            )
        assertEquals("Hello!", result)
    }
}
