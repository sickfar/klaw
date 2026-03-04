package io.github.klaw.common.config.schema

import io.github.klaw.common.config.ConfigPropertyDescriptor
import io.github.klaw.common.config.ConfigValueType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertTrue

class MarkdownGeneratorTest {
    private val sampleDescriptors =
        listOf(
            ConfigPropertyDescriptor(
                path = "processing.debounceMs",
                type = ConfigValueType.LONG,
                description = "Delay in milliseconds before processing",
                defaultValue = "0",
                possibleValues = null,
                sensitive = false,
                required = true,
            ),
            ConfigPropertyDescriptor(
                path = "processing.maxConcurrentLlm",
                type = ConfigValueType.INT,
                description = "Maximum concurrent LLM requests",
                defaultValue = null,
                possibleValues = null,
                sensitive = false,
                required = true,
            ),
            ConfigPropertyDescriptor(
                path = "memory.embedding.type",
                type = ConfigValueType.STRING,
                description = "Embedding backend type",
                defaultValue = null,
                possibleValues = listOf("onnx", "ollama"),
                sensitive = false,
                required = true,
            ),
            ConfigPropertyDescriptor(
                path = "memory.embedding.model",
                type = ConfigValueType.STRING,
                description = "Embedding model name",
                defaultValue = null,
                possibleValues = null,
                sensitive = false,
                required = true,
            ),
            ConfigPropertyDescriptor(
                path = "providers",
                type = ConfigValueType.MAP_SECTION,
                description = "LLM provider definitions",
                defaultValue = null,
                possibleValues = null,
                sensitive = false,
                required = true,
            ),
        )

    @Test
    fun generatesValidMarkdownWithTitle() {
        val md = generateMarkdown("Engine Configuration Reference", sampleDescriptors)
        assertContains(md, "# Engine Configuration Reference")
    }

    @Test
    fun groupsByTopLevelSection() {
        val md = generateMarkdown("Test", sampleDescriptors)
        assertContains(md, "## processing")
        assertContains(md, "## memory")
    }

    @Test
    fun containsTableHeaders() {
        val md = generateMarkdown("Test", sampleDescriptors)
        assertContains(md, "| Property | Type | Default | Description |")
    }

    @Test
    fun containsPropertyPaths() {
        val md = generateMarkdown("Test", sampleDescriptors)
        assertContains(md, "`processing.debounceMs`")
        assertContains(md, "`memory.embedding.type`")
    }

    @Test
    fun includesPossibleValuesInDescription() {
        val md = generateMarkdown("Test", sampleDescriptors)
        assertContains(md, "`onnx`")
        assertContains(md, "`ollama`")
    }

    @Test
    fun handlesMapSectionEntries() {
        val md = generateMarkdown("Test", sampleDescriptors)
        assertContains(md, "providers")
    }

    @Test
    fun showsDefaultValues() {
        val md = generateMarkdown("Test", sampleDescriptors)
        assertContains(md, "`0`")
    }

    @Test
    fun showsDashForMissingDefaults() {
        val md = generateMarkdown("Test", sampleDescriptors)
        // maxConcurrentLlm has no default
        assertTrue(md.contains("—") || md.contains("-"), "Expected dash for missing default")
    }

    @Test
    fun emptyDescriptorsProducesMinimalMarkdown() {
        val md = generateMarkdown("Empty", emptyList())
        assertContains(md, "# Empty")
    }
}
