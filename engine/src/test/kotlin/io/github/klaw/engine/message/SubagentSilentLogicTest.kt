package io.github.klaw.engine.message

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SubagentSilentLogicTest {
    @Test
    fun `response with silent=true is silent`() {
        assertTrue(isSilent("""{"silent":true}"""))
    }

    @Test
    fun `response without silent field is not silent`() {
        assertFalse(isSilent("""{"result":"ok"}"""))
    }

    @Test
    fun `response with silent=false is not silent`() {
        assertFalse(isSilent("""{"silent":false}"""))
    }

    @Test
    fun `non-JSON plain text is not silent (safe default)`() {
        assertFalse(isSilent("plain text response"))
    }

    @Test
    fun `parse failure treated as not silent (safe default)`() {
        assertFalse(isSilent("{invalid json}"))
    }
}
