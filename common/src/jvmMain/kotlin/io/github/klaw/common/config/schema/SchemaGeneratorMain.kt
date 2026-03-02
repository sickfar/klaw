package io.github.klaw.common.config.schema

import io.github.klaw.common.config.ComposeConfig
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
        ".context.slidingWindow" to buildJsonObject { put("exclusiveMinimum", 0) },
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
    val outputDir = File(args.firstOrNull() ?: error("Usage: SchemaGeneratorMain <output-dir>"))
    outputDir.mkdirs()

    val engineSchema = generateJsonSchema(EngineConfig.serializer().descriptor, ENGINE_OVERRIDES)
    val gatewaySchema = generateJsonSchema(GatewayConfig.serializer().descriptor)
    val composeSchema = generateJsonSchema(ComposeConfig.serializer().descriptor)

    // Write schema JSON files (for doc/ and validation)
    File(outputDir, "engine.schema.json").writeText(
        prettyJson.encodeToString(JsonObject.serializer(), engineSchema) + "\n",
    )
    File(outputDir, "gateway.schema.json").writeText(
        prettyJson.encodeToString(JsonObject.serializer(), gatewaySchema) + "\n",
    )
    File(outputDir, "compose.schema.json").writeText(
        prettyJson.encodeToString(JsonObject.serializer(), composeSchema) + "\n",
    )

    // Write GeneratedSchemas.kt source file for KMP access
    val kotlinDir = File(args.getOrNull(1) ?: error("Usage: SchemaGeneratorMain <schema-dir> <kotlin-dir>"))
    kotlinDir.mkdirs()

    // Escape $ in JSON to prevent Kotlin string template interpolation in raw strings
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

private fun String.escapeDollar(): String = replace("$", "\${'\$'}")
