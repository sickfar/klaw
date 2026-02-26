package io.github.klaw.engine.workspace

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeartbeatParserTest {
    private val parser = HeartbeatParser()

    @Test
    fun `parses basic task with cron and message`() {
        val content =
            """
            ## Morning Check
            - Cron: 0 0 9 * * ?
            - Message: Check email
            """.trimIndent()
        val tasks = parser.parse(content)
        assertEquals(1, tasks.size)
        assertEquals("Morning Check", tasks[0].name)
        assertEquals("0 0 9 * * ?", tasks[0].cron)
        assertEquals("Check email", tasks[0].message)
        assertEquals(null, tasks[0].model)
        assertEquals(null, tasks[0].injectInto)
    }

    @Test
    fun `parses task with optional model`() {
        val content =
            """
            ## Daily Digest
            - Cron: 0 0 8 * * ?
            - Message: Summarize news
            - Model: glm/glm-4-plus
            """.trimIndent()
        val tasks = parser.parse(content)
        assertEquals("glm/glm-4-plus", tasks[0].model)
    }

    @Test
    fun `parses task with injectInto`() {
        val content =
            """
            ## Alert Task
            - Cron: 0 */30 * * * ?
            - Message: Check alerts
            - InjectInto: telegram_123456
            """.trimIndent()
        val tasks = parser.parse(content)
        assertEquals("telegram_123456", tasks[0].injectInto)
    }

    @Test
    fun `parses multiple tasks from single file`() {
        val content =
            """
            ## Task One
            - Cron: 0 0 9 * * ?
            - Message: First task

            ## Task Two
            - Cron: 0 0 18 * * ?
            - Message: Second task
            """.trimIndent()
        val tasks = parser.parse(content)
        assertEquals(2, tasks.size)
        assertEquals("Task One", tasks[0].name)
        assertEquals("Task Two", tasks[1].name)
    }

    @Test
    fun `handles empty HEARTBEAT_md`() {
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse("   \n  ").isEmpty())
    }

    @Test
    fun `handles malformed entry gracefully`() {
        val content =
            """
            ## Bad Task
            - NoCron: here
            - Message: Some message
            """.trimIndent()
        // Missing cron → skip task
        assertTrue(parser.parse(content).isEmpty())
    }

    @Test
    fun `handles missing message gracefully`() {
        val content =
            """
            ## Bad Task
            - Cron: 0 0 9 * * ?
            """.trimIndent()
        assertTrue(parser.parse(content).isEmpty())
    }

    @Test
    fun `parses OpenClaw standard format with all fields`() {
        val content =
            """
            ## Weekly Report
            - Cron: 0 0 9 ? * MON
            - Message: Generate weekly report and send to management
            - Model: glm/glm-4-plus
            - InjectInto: telegram_999
            """.trimIndent()
        val tasks = parser.parse(content)
        assertEquals(1, tasks.size)
        with(tasks[0]) {
            assertEquals("Weekly Report", name)
            assertEquals("0 0 9 ? * MON", cron)
            assertEquals("Generate weekly report and send to management", message)
            assertEquals("glm/glm-4-plus", model)
            assertEquals("telegram_999", injectInto)
        }
    }

    @Test
    fun `ignores non-task heading content before first task`() {
        val content =
            """
            # HEARTBEAT

            This file defines recurring tasks.

            ## Morning Check
            - Cron: 0 0 9 * * ?
            - Message: Check email
            """.trimIndent()
        val tasks = parser.parse(content)
        assertEquals(1, tasks.size)
        assertEquals("Morning Check", tasks[0].name)
    }
}
