package io.github.klaw.common.config.schema

import io.github.klaw.common.config.ComposeConfig
import io.github.klaw.common.config.ConfigPropertyDescriptor
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.GatewayConfig
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

fun engineOverrides(): Map<String, JsonObject> = ENGINE_OVERRIDES

private val ENGINE_OVERRIDES =
    mapOf(
        ".memory.chunking.size" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".memory.chunking.overlap" to buildJsonObject { put("minimum", 0) },
        ".memory.search.topK" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".context.defaultBudgetTokens" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".context.subagentHistory" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".processing.debounceMs" to buildJsonObject { put("minimum", 0) },
        ".processing.maxConcurrentLlm" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".processing.maxToolCallRounds" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".processing.maxToolOutputChars" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".processing.maxDebounceEntries" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".llm.maxRetries" to buildJsonObject { put("minimum", 0) },
        ".llm.requestTimeoutMs" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".llm.initialBackoffMs" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".llm.backoffMultiplier" to buildJsonObject { put("minimum", 1.0) },
        ".files.maxFileSizeBytes" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".autoRag.topK" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".autoRag.maxTokens" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".autoRag.relevanceThreshold" to buildJsonObject { put("exclusiveMinimum", 0.0) },
        ".autoRag.minMessageTokens" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".hostExecution.askTimeoutMin" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".hostExecution.preValidation.riskThreshold" to buildJsonObject { put("minimum", 0) },
        ".hostExecution.preValidation.timeoutMs" to buildJsonObject { put("exclusiveMinimum", 0) },
        ".skills.maxInlineSkills" to buildJsonObject { put("exclusiveMinimum", 0) },
    )

private val prettyJson =
    kotlinx.serialization.json.Json {
        prettyPrint = true
    }

fun main(args: Array<String>) {
    // generatedDir: build output for JSON schemas + markdown (not tracked in git)
    // kotlinDir: src/commonMain/.../schema/ for GeneratedSchemas.kt + GeneratedConfigDescriptors.kt
    val usage = "Usage: SchemaGeneratorMain <generated-dir> <kotlin-dir>"
    val generatedDir = File(args.getOrNull(0) ?: error(usage))
    generatedDir.mkdirs()

    val engineSchema = generateJsonSchema(EngineConfig.serializer().descriptor, ENGINE_OVERRIDES)
    val gatewaySchema = generateJsonSchema(GatewayConfig.serializer().descriptor)
    val composeSchema = generateJsonSchema(ComposeConfig.serializer().descriptor)

    // Write schema JSON files
    File(generatedDir, "engine.schema.json").writeText(
        prettyJson.encodeToString(JsonObject.serializer(), engineSchema) + "\n",
    )
    File(generatedDir, "gateway.schema.json").writeText(
        prettyJson.encodeToString(JsonObject.serializer(), gatewaySchema) + "\n",
    )
    File(generatedDir, "compose.schema.json").writeText(
        prettyJson.encodeToString(JsonObject.serializer(), composeSchema) + "\n",
    )

    // Generate config property descriptors
    val engineDescriptors =
        generateDescriptors(
            EngineConfig.serializer().descriptor,
            EngineConfig::class.java,
        )
    val gatewayDescriptors =
        generateDescriptors(
            GatewayConfig.serializer().descriptor,
            GatewayConfig::class.java,
        )

    // Write markdown reference docs
    File(generatedDir, "engine-config-reference.md").writeText(
        generateMarkdown("Engine Configuration Reference", engineDescriptors),
    )
    File(generatedDir, "gateway-config-reference.md").writeText(
        generateMarkdown("Gateway Configuration Reference", gatewayDescriptors),
    )

    // Write GeneratedSchemas.kt and GeneratedConfigDescriptors.kt
    val kotlinDir = File(args.getOrNull(1) ?: error(usage))
    kotlinDir.mkdirs()

    writeGeneratedSchemas(kotlinDir, engineSchema, gatewaySchema, composeSchema)
    writeGeneratedDescriptors(kotlinDir, engineDescriptors, gatewayDescriptors)
}

private fun writeGeneratedSchemas(
    kotlinDir: File,
    engineSchema: JsonObject,
    gatewaySchema: JsonObject,
    composeSchema: JsonObject,
) {
    val engineStr = prettyJson.encodeToString(JsonObject.serializer(), engineSchema).escapeDollar()
    val gatewayStr = prettyJson.encodeToString(JsonObject.serializer(), gatewaySchema).escapeDollar()
    val composeStr = prettyJson.encodeToString(JsonObject.serializer(), composeSchema).escapeDollar()

    @Suppress("MaxLineLength")
    File(kotlinDir, "GeneratedSchemas.kt").writeText(
        buildString {
            appendLine("package io.github.klaw.common.config.schema")
            appendLine()
            appendLine("// AUTO-GENERATED — do not edit manually. Run generateSchemas task to regenerate.")
            appendLine()
            appendLine("object GeneratedSchemas {")
            appendLine("    val ENGINE: String =")
            appendLine("        \"\"\"")
            appendLine(engineStr.prependIndent("        "))
            appendLine("        \"\"\".trimIndent()")
            appendLine()
            appendLine("    val GATEWAY: String =")
            appendLine("        \"\"\"")
            appendLine(gatewayStr.prependIndent("        "))
            appendLine("        \"\"\".trimIndent()")
            appendLine()
            appendLine("    val COMPOSE: String =")
            appendLine("        \"\"\"")
            appendLine(composeStr.prependIndent("        "))
            appendLine("        \"\"\".trimIndent()")
            appendLine("}")
        },
    )
}

private fun writeGeneratedDescriptors(
    kotlinDir: File,
    engineDescriptors: List<ConfigPropertyDescriptor>,
    gatewayDescriptors: List<ConfigPropertyDescriptor>,
) {
    File(kotlinDir, "GeneratedConfigDescriptors.kt").writeText(
        buildString {
            appendLine("package io.github.klaw.common.config.schema")
            appendLine()
            appendLine("import io.github.klaw.common.config.ConfigPropertyDescriptor")
            appendLine("import io.github.klaw.common.config.ConfigValueType")
            appendLine()
            appendLine("// AUTO-GENERATED — do not edit manually. Run generateSchemas task to regenerate.")
            appendLine()
            appendLine("object GeneratedConfigDescriptors {")
            appendLine("    val ENGINE: List<ConfigPropertyDescriptor> = listOf(")
            for (desc in engineDescriptors) {
                appendLine("        ${formatDescriptorLiteral(desc)},")
            }
            appendLine("    )")
            appendLine()
            appendLine("    val GATEWAY: List<ConfigPropertyDescriptor> = listOf(")
            for (desc in gatewayDescriptors) {
                appendLine("        ${formatDescriptorLiteral(desc)},")
            }
            appendLine("    )")
            appendLine("}")
        },
    )
}

private fun formatDescriptorLiteral(desc: ConfigPropertyDescriptor): String {
    val possibleStr =
        if (desc.possibleValues != null) {
            "listOf(${desc.possibleValues.joinToString(", ") { "\"${it.escapeKotlinString()}\"" }})"
        } else {
            "null"
        }
    val defaultStr =
        if (desc.defaultValue != null) {
            "\"${desc.defaultValue.escapeKotlinString()}\""
        } else {
            "null"
        }
    val descStr = desc.description.escapeKotlinString()
    val indent = "            "
    return buildString {
        append("ConfigPropertyDescriptor(\n")
        append("${indent}\"${desc.path}\",\n")
        append("${indent}ConfigValueType.${desc.type.name},\n")
        append("${indent}\"$descStr\",\n")
        append("${indent}$defaultStr,\n")
        append("${indent}$possibleStr,\n")
        append("${indent}${desc.sensitive},\n")
        append("${indent}${desc.required},\n")
        append("        )")
    }
}

private fun String.escapeDollar(): String = replace("$", "\${'\$'}")

private fun String.escapeKotlinString(): String =
    replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\$", "\\\$")
