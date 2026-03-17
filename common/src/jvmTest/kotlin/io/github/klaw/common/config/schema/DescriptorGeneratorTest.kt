package io.github.klaw.common.config.schema

import io.github.klaw.common.config.ConfigPropertyDescriptor
import io.github.klaw.common.config.ConfigValueType
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.GatewayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DescriptorGeneratorTest {
    private val engineDescriptors: List<ConfigPropertyDescriptor> by lazy {
        generateDescriptors(
            EngineConfig.serializer().descriptor,
            EngineConfig::class.java,
        )
    }

    private val gatewayDescriptors: List<ConfigPropertyDescriptor> by lazy {
        generateDescriptors(
            GatewayConfig.serializer().descriptor,
            GatewayConfig::class.java,
        )
    }

    private fun findDescriptor(
        descriptors: List<ConfigPropertyDescriptor>,
        path: String,
    ): ConfigPropertyDescriptor? = descriptors.find { it.path == path }

    // --- Path generation ---

    @Test
    fun generatesProcessingDebounceMs() {
        val desc = findDescriptor(engineDescriptors, "processing.debounceMs")
        assertNotNull(desc, "processing.debounceMs not found")
        assertEquals(ConfigValueType.LONG, desc.type)
    }

    @Test
    fun generatesMemoryEmbeddingType() {
        val desc = findDescriptor(engineDescriptors, "memory.embedding.type")
        assertNotNull(desc, "memory.embedding.type not found")
        assertEquals(ConfigValueType.STRING, desc.type)
    }

    @Test
    fun generatesContextDefaultBudgetTokens() {
        val desc = findDescriptor(engineDescriptors, "context.defaultBudgetTokens")
        assertNotNull(desc, "context.defaultBudgetTokens not found")
        assertEquals(ConfigValueType.INT, desc.type)
    }

    @Test
    fun generatesRoutingDefault() {
        val desc = findDescriptor(engineDescriptors, "routing.default")
        assertNotNull(desc, "routing.default not found")
        assertEquals(ConfigValueType.STRING, desc.type)
    }

    @Test
    fun generatesLlmBackoffMultiplier() {
        val desc = findDescriptor(engineDescriptors, "llm.backoffMultiplier")
        assertNotNull(desc, "llm.backoffMultiplier not found")
        assertEquals(ConfigValueType.DOUBLE, desc.type)
    }

    @Test
    fun generatesLoggingSubagentConversations() {
        val desc = findDescriptor(engineDescriptors, "logging.subagentConversations")
        assertNotNull(desc, "logging.subagentConversations not found")
        assertEquals(ConfigValueType.BOOLEAN, desc.type)
    }

    // --- Type detection ---

    @Test
    fun detectsMapSection() {
        val desc = findDescriptor(engineDescriptors, "providers")
        assertNotNull(desc, "providers not found")
        assertEquals(ConfigValueType.MAP_SECTION, desc.type)
    }

    @Test
    fun detectsListString() {
        val desc = findDescriptor(engineDescriptors, "routing.fallback")
        assertNotNull(desc, "routing.fallback not found")
        assertEquals(ConfigValueType.LIST_STRING, desc.type)
    }

    // --- ConfigDoc description ---

    @Test
    fun readsDescriptionFromAnnotation() {
        val desc = findDescriptor(engineDescriptors, "processing.debounceMs")
        assertNotNull(desc)
        assertTrue(desc.description.isNotEmpty(), "Description should not be empty")
        assertTrue(
            desc.description.contains("milliseconds", ignoreCase = true),
            "Expected 'milliseconds' in: ${desc.description}",
        )
    }

    // --- possibleValues ---

    @Test
    fun readsPossibleValuesFromAnnotation() {
        val desc = findDescriptor(engineDescriptors, "memory.embedding.type")
        assertNotNull(desc)
        assertNotNull(desc.possibleValues, "possibleValues should not be null")
        assertTrue(desc.possibleValues!!.contains("onnx"))
        assertTrue(desc.possibleValues!!.contains("ollama"))
    }

    // --- Sensitive ---

    @Test
    fun marksSensitiveProperties() {
        // providers.*.apiKey is inside a map value — we check via a different path
        // TelegramConfig.token is sensitive
        val desc = findDescriptor(gatewayDescriptors, "channels.telegram.token")
        assertNotNull(desc, "channels.telegram.token not found")
        assertTrue(desc.sensitive, "telegram.token should be sensitive")
    }

    @Test
    fun nonSensitivePropertyNotMarked() {
        val desc = findDescriptor(engineDescriptors, "routing.default")
        assertNotNull(desc)
        assertFalse(desc.sensitive, "routing.default should not be sensitive")
    }

    // --- Required vs Optional ---

    @Test
    fun detectsRequiredProperty() {
        val desc = findDescriptor(engineDescriptors, "processing.debounceMs")
        assertNotNull(desc)
        assertTrue(desc.required, "processing.debounceMs should be required")
    }

    @Test
    fun detectsOptionalProperty() {
        val desc = findDescriptor(engineDescriptors, "llm.maxRetries")
        assertNotNull(desc)
        assertFalse(desc.required, "llm.maxRetries should be optional")
    }

    // --- Skips @Transient ---

    @Test
    fun skipsTransientFields() {
        val desc = findDescriptor(engineDescriptors, "codeExecution.noPrivileged")
        assertEquals(null, desc, "noPrivileged should be skipped (it is @Transient)")
    }

    // --- Gateway descriptors ---

    @Test
    fun generatesGatewayDescriptors() {
        assertTrue(gatewayDescriptors.isNotEmpty(), "Gateway descriptors should not be empty")
        val desc = findDescriptor(gatewayDescriptors, "channels.telegram.token")
        assertNotNull(desc, "channels.telegram.token not found")
        assertEquals(ConfigValueType.STRING, desc.type)
    }

    @Test
    fun generatesGatewayLocalWsPort() {
        val desc = findDescriptor(gatewayDescriptors, "channels.localWs.port")
        assertNotNull(desc, "channels.localWs.port not found")
        assertEquals(ConfigValueType.INT, desc.type)
    }

    // --- Discord token sensitive ---

    @Test
    fun discordTokenIsSensitive() {
        val desc = findDescriptor(gatewayDescriptors, "channels.discord.token")
        assertNotNull(desc, "channels.discord.token not found")
        assertTrue(desc.sensitive, "discord.token should be sensitive")
    }
}
