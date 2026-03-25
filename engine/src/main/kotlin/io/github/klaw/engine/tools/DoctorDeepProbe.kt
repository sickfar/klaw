package io.github.klaw.engine.tools

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.engine.mcp.McpToolRegistry
import io.github.klaw.engine.memory.EmbeddingService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val logger = KotlinLogging.logger {}

@Singleton
class DoctorDeepProbe(
    private val embeddingService: EmbeddingService,
    private val engineHealthProvider: EngineHealthProvider,
    private val mcpToolRegistry: McpToolRegistry,
    private val config: EngineConfig,
) {
    suspend fun probe(): String {
        logger.debug { "running deep probe" }
        val embedding = probeEmbedding()
        val database = probeDatabase()
        val providers = probeProviders()
        val mcpServers = probeMcpServers()
        return buildJsonResponse(embedding, database, providers, mcpServers)
    }

    private suspend fun probeEmbedding(): JsonObject =
        try {
            embeddingService.embed("test")
            val type = engineHealthProvider.classifyEmbeddingService()
            logger.trace { "embedding probe ok, type=$type" }
            buildJsonObject {
                put("status", "ok")
                put("type", type)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            logger.debug { "embedding probe failed: ${e::class.simpleName}" }
            buildJsonObject {
                put("status", "fail")
                put("error", e::class.simpleName ?: "unknown")
            }
        }

    private suspend fun probeDatabase(): JsonObject {
        val ok = engineHealthProvider.checkDatabase()
        logger.trace { "database probe: ok=$ok" }
        return buildJsonObject {
            put("status", if (ok) "ok" else "fail")
        }
    }

    private fun probeProviders() =
        buildJsonArray {
            for ((name, provider) in config.providers) {
                logger.trace { "listing provider: $name" }
                add(
                    buildJsonObject {
                        put("name", name)
                        put("type", provider.type ?: "unknown")
                    },
                )
            }
        }

    private fun probeMcpServers() =
        buildJsonArray {
            for (name in mcpToolRegistry.serverNames()) {
                logger.trace { "listing MCP server: $name" }
                add(
                    buildJsonObject {
                        put("name", name)
                    },
                )
            }
        }

    private fun buildJsonResponse(
        embedding: JsonObject,
        database: JsonObject,
        providers: kotlinx.serialization.json.JsonArray,
        mcpServers: kotlinx.serialization.json.JsonArray,
    ): String {
        val result =
            buildJsonObject {
                put("embedding", embedding)
                put("database", database)
                put("providers", providers)
                put("mcpServers", mcpServers)
            }
        return probeJson.encodeToString(JsonObject.serializer(), result)
    }

    private companion object {
        private val probeJson = kotlinx.serialization.json.Json { prettyPrint = false }
    }
}
