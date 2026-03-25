package io.github.klaw.engine.llm

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ApiErrorDetailTest {
    @Test
    fun `extracts error message from nested error object`() {
        val body = """{"error":{"message":"rate limit exceeded","type":"rate_limit_error"}}"""
        assertEquals("rate limit exceeded", extractApiErrorDetail(body))
    }

    @Test
    fun `extracts message from top-level message field`() {
        val body = """{"message":"invalid model"}"""
        assertEquals("invalid model", extractApiErrorDetail(body))
    }

    @Test
    fun `returns first 200 chars for non-JSON body`() {
        val body = "This is a plain text error response"
        assertEquals("This is a plain text error response", extractApiErrorDetail(body))
    }

    @Test
    fun `truncates long non-JSON body to 200 chars`() {
        val body = "x".repeat(300)
        assertEquals("x".repeat(200), extractApiErrorDetail(body))
    }

    @Test
    fun `returns empty marker for null body`() {
        assertEquals("<empty>", extractApiErrorDetail(null))
    }

    @Test
    fun `returns empty marker for empty string`() {
        assertEquals("<empty>", extractApiErrorDetail(""))
    }

    @Test
    fun `returns empty marker for blank string`() {
        assertEquals("<empty>", extractApiErrorDetail("   "))
    }

    @Test
    fun `handles JSON without error or message fields`() {
        val body = """{"code":400,"status":"bad_request"}"""
        assertEquals(body, extractApiErrorDetail(body))
    }

    @Test
    fun `truncates long JSON without known fields`() {
        val body = """{"data":"${"y".repeat(300)}"}"""
        assertEquals(body.take(200), extractApiErrorDetail(body))
    }
}
