package io.github.klaw.engine.config

import io.github.klaw.common.config.EngineConfig
import io.micronaut.test.extensions.junit5.annotation.MicronautTest
import jakarta.inject.Inject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

@MicronautTest
class EngineConfigLoadTest {
    @Inject
    lateinit var config: EngineConfig

    @Test
    fun `engine config loads from classpath`() {
        assertNotNull(config)
    }

    @Test
    fun `providers are loaded`() {
        assertNotNull(config.providers["test"])
        assertEquals("openai-compatible", config.providers["test"]!!.type)
    }

    @Test
    fun `routing default model is set`() {
        assertEquals("test/test-model", config.routing.default)
    }

    @Test
    fun `processing config is loaded`() {
        assertEquals(100L, config.processing.debounceMs)
        assertEquals(2, config.processing.maxConcurrentLlm)
        assertEquals(5, config.processing.maxToolCallRounds)
    }

    @Test
    fun `context config is loaded`() {
        assertEquals(4096, config.context.defaultBudgetTokens)
        assertEquals(10, config.context.slidingWindow)
        assertEquals(5, config.context.subagentHistory)
    }
}
