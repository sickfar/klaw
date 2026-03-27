package io.github.klaw.common.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DailyConsolidationConfigTest {
    private fun minimalEngineJson(consolidationBlock: String = ""): String {
        val memoryContent =
            if (consolidationBlock.isNotEmpty()) {
                """"memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}, $consolidationBlock}"""
            } else {
                """"memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}}"""
            }
        return """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b", "fallback": [], "tasks": {"summarization": "a/b", "subagent": "a/b"}},
  $memoryContent,
  "context": {"tokenBudget": 100, "subagentHistory": 3},
  "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
}
            """.trimIndent()
    }

    @Test
    fun `parse consolidation with all fields`() {
        val json =
            minimalEngineJson(
                """
                "consolidation": {
                  "enabled": true,
                  "cron": "0 30 3 * * ?",
                  "model": "glm/glm-5",
                  "excludeChannels": ["telegram_123", "discord_456"],
                  "category": "daily-notes",
                  "minMessages": 10
                }
                """.trimIndent(),
            )
        val config = parseEngineConfig(json)
        assertTrue(config.memory.consolidation.enabled)
        assertEquals("0 30 3 * * ?", config.memory.consolidation.cron)
        assertEquals("glm/glm-5", config.memory.consolidation.model)
        assertEquals(listOf("telegram_123", "discord_456"), config.memory.consolidation.excludeChannels)
        assertEquals("daily-notes", config.memory.consolidation.category)
        assertEquals(10, config.memory.consolidation.minMessages)
    }

    @Test
    fun `parse consolidation with empty section uses defaults`() {
        val json = minimalEngineJson(""""consolidation": {}""")
        val config = parseEngineConfig(json)
        assertFalse(config.memory.consolidation.enabled)
        assertEquals("0 0 0 * * ?", config.memory.consolidation.cron)
        assertEquals("", config.memory.consolidation.model)
        assertTrue(
            config.memory.consolidation.excludeChannels
                .isEmpty(),
        )
        assertEquals("daily-summary", config.memory.consolidation.category)
        assertEquals(5, config.memory.consolidation.minMessages)
    }

    @Test
    fun `parse consolidation absent from JSON uses defaults`() {
        val json = minimalEngineJson()
        val config = parseEngineConfig(json)
        assertFalse(config.memory.consolidation.enabled)
        assertEquals("0 0 0 * * ?", config.memory.consolidation.cron)
        assertEquals("", config.memory.consolidation.model)
        assertTrue(
            config.memory.consolidation.excludeChannels
                .isEmpty(),
        )
        assertEquals("daily-summary", config.memory.consolidation.category)
        assertEquals(5, config.memory.consolidation.minMessages)
    }

    @Test
    fun `validation - minMessages zero fails`() {
        assertFailsWith<IllegalArgumentException> {
            DailyConsolidationConfig(minMessages = 0)
        }
    }

    @Test
    fun `validation - minMessages negative fails`() {
        assertFailsWith<IllegalArgumentException> {
            DailyConsolidationConfig(minMessages = -1)
        }
    }

    @Test
    fun `validation - blank category fails`() {
        assertFailsWith<IllegalArgumentException> {
            DailyConsolidationConfig(category = "")
        }
    }

    @Test
    fun `validation - whitespace-only category fails`() {
        assertFailsWith<IllegalArgumentException> {
            DailyConsolidationConfig(category = "   ")
        }
    }

    @Test
    fun `task routing config includes consolidation field with default`() {
        val json = minimalEngineJson()
        val config = parseEngineConfig(json)
        assertEquals("", config.routing.tasks.consolidation)
    }

    @Test
    fun `task routing config parses consolidation model`() {
        val json =
            """
{
  "providers": {},
  "models": {},
  "routing": {"default": "a/b", "fallback": [], "tasks": {"summarization": "a/b", "subagent": "a/b", "consolidation": "deepseek/deepseek-chat"}},
  "memory": {"embedding": {"type": "onnx", "model": "m"}, "chunking": {"size": 100, "overlap": 10}, "search": {"topK": 5}},
  "context": {"tokenBudget": 100, "subagentHistory": 3},
  "processing": {"debounceMs": 100, "maxConcurrentLlm": 1, "maxToolCallRounds": 1}
}
            """.trimIndent()
        val config = parseEngineConfig(json)
        assertEquals("deepseek/deepseek-chat", config.routing.tasks.consolidation)
    }
}
