package io.github.klaw.common.config.schema

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Suppress("LongMethod")
fun engineJsonSchema(): JsonObject =
    buildJsonObject {
        put("\$schema", "http://json-schema.org/draft-07/schema#")
        put("type", "object")

        val providerSchema =
            objectSchema(
                required = listOf("type", "endpoint"),
                properties =
                    mapOf(
                        "type" to stringProp(),
                        "endpoint" to stringProp(),
                        "apiKey" to stringProp(),
                    ),
            )

        val modelSchema =
            objectSchema(
                properties =
                    mapOf(
                        "maxTokens" to intProp(),
                        "contextBudget" to intProp(),
                        "temperature" to numberProp(),
                    ),
            )

        val taskRoutingSchema =
            objectSchema(
                required = listOf("summarization", "subagent"),
                properties =
                    mapOf(
                        "summarization" to stringProp(),
                        "subagent" to stringProp(),
                    ),
            )

        val routingSchema =
            objectSchema(
                required = listOf("default", "tasks"),
                properties =
                    mapOf(
                        "default" to stringProp(),
                        "fallback" to arraySchema(stringProp()),
                        "tasks" to taskRoutingSchema,
                    ),
            )

        val embeddingSchema =
            objectSchema(
                required = listOf("type", "model"),
                properties =
                    mapOf(
                        "type" to stringProp(),
                        "model" to stringProp(),
                    ),
            )

        val chunkingSchema =
            objectSchema(
                required = listOf("size", "overlap"),
                properties =
                    mapOf(
                        "size" to intProp(exclusiveMinimum = 0),
                        "overlap" to intProp(minimum = 0),
                    ),
            )

        val searchSchema =
            objectSchema(
                required = listOf("topK"),
                properties =
                    mapOf(
                        "topK" to intProp(exclusiveMinimum = 0),
                    ),
            )

        val memorySchema =
            objectSchema(
                required = listOf("embedding", "chunking", "search"),
                properties =
                    mapOf(
                        "embedding" to embeddingSchema,
                        "chunking" to chunkingSchema,
                        "search" to searchSchema,
                    ),
            )

        val contextSchema =
            objectSchema(
                required = listOf("defaultBudgetTokens", "slidingWindow", "subagentHistory"),
                properties =
                    mapOf(
                        "defaultBudgetTokens" to intProp(exclusiveMinimum = 0),
                        "slidingWindow" to intProp(exclusiveMinimum = 0),
                        "subagentHistory" to intProp(exclusiveMinimum = 0),
                    ),
            )

        val processingSchema =
            objectSchema(
                required = listOf("debounceMs", "maxConcurrentLlm", "maxToolCallRounds"),
                properties =
                    mapOf(
                        "debounceMs" to longProp(minimum = 0),
                        "maxConcurrentLlm" to intProp(exclusiveMinimum = 0),
                        "maxToolCallRounds" to intProp(exclusiveMinimum = 0),
                        "maxToolOutputChars" to intProp(exclusiveMinimum = 0),
                        "maxDebounceEntries" to intProp(exclusiveMinimum = 0),
                    ),
            )

        val llmSchema =
            objectSchema(
                properties =
                    mapOf(
                        "maxRetries" to intProp(minimum = 0),
                        "requestTimeoutMs" to longProp(exclusiveMinimum = 0),
                        "initialBackoffMs" to longProp(exclusiveMinimum = 0),
                        "backoffMultiplier" to numberProp(minimum = 1.0),
                    ),
            )

        val loggingSchema =
            objectSchema(
                properties =
                    mapOf(
                        "subagentConversations" to boolProp(),
                    ),
            )

        val codeExecutionSchema =
            objectSchema(
                properties =
                    mapOf(
                        "dockerImage" to stringProp(),
                        "timeout" to intProp(),
                        "allowNetwork" to boolProp(),
                        "maxMemory" to stringProp(),
                        "maxCpus" to stringProp(),
                        "readOnlyRootfs" to boolProp(),
                        "keepAlive" to boolProp(),
                        "keepAliveIdleTimeoutMin" to intProp(),
                        "keepAliveMaxExecutions" to intProp(),
                        "volumeMounts" to arraySchema(stringProp()),
                    ),
            )

        val filesSchema =
            objectSchema(
                properties =
                    mapOf(
                        "maxFileSizeBytes" to longProp(exclusiveMinimum = 0),
                    ),
            )

        val commandSchema =
            objectSchema(
                required = listOf("name", "description"),
                properties =
                    mapOf(
                        "name" to stringProp(),
                        "description" to stringProp(),
                    ),
            )

        val openClawSyncSchema =
            objectSchema(
                properties =
                    mapOf(
                        "memoryMd" to boolProp(),
                        "dailyLogs" to boolProp(),
                        "userMd" to boolProp(),
                    ),
            )

        val openClawCompatSchema =
            objectSchema(
                properties =
                    mapOf(
                        "enabled" to boolProp(),
                        "sync" to openClawSyncSchema,
                    ),
            )

        val compatibilitySchema =
            objectSchema(
                properties =
                    mapOf(
                        "openclaw" to openClawCompatSchema,
                    ),
            )

        val autoRagSchema =
            objectSchema(
                properties =
                    mapOf(
                        "enabled" to boolProp(),
                        "topK" to intProp(exclusiveMinimum = 0),
                        "maxTokens" to intProp(exclusiveMinimum = 0),
                        "relevanceThreshold" to numberProp(exclusiveMinimum = 0.0),
                        "minMessageTokens" to intProp(exclusiveMinimum = 0),
                    ),
            )

        val docsSchema =
            objectSchema(
                properties =
                    mapOf(
                        "enabled" to boolProp(),
                    ),
            )

        val preValidationSchema =
            objectSchema(
                properties =
                    mapOf(
                        "enabled" to boolProp(),
                        "model" to stringProp(),
                        "riskThreshold" to intProp(minimum = 0),
                        "timeoutMs" to longProp(exclusiveMinimum = 0),
                    ),
            )

        val hostExecutionSchema =
            objectSchema(
                properties =
                    mapOf(
                        "enabled" to boolProp(),
                        "allowList" to arraySchema(stringProp()),
                        "notifyList" to arraySchema(stringProp()),
                        "preValidation" to preValidationSchema,
                        "askTimeoutMin" to intProp(exclusiveMinimum = 0),
                    ),
            )

        put(
            "properties",
            buildJsonObject {
                put("providers", mapSchema(providerSchema))
                put("models", mapSchema(modelSchema))
                put("routing", routingSchema)
                put("memory", memorySchema)
                put("context", contextSchema)
                put("processing", processingSchema)
                put("llm", llmSchema)
                put("logging", loggingSchema)
                put("codeExecution", codeExecutionSchema)
                put("files", filesSchema)
                put("commands", arraySchema(commandSchema))
                put("compatibility", compatibilitySchema)
                put("autoRag", autoRagSchema)
                put("docs", docsSchema)
                put("hostExecution", hostExecutionSchema)
            },
        )

        put(
            "required",
            kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive("providers"))
                add(JsonPrimitive("models"))
                add(JsonPrimitive("routing"))
                add(JsonPrimitive("memory"))
                add(JsonPrimitive("context"))
                add(JsonPrimitive("processing"))
            },
        )

        put("additionalProperties", JsonPrimitive(true))
    }
