package io.github.klaw.engine.mcp

import io.github.klaw.common.config.McpConfig
import io.github.klaw.common.config.parseMcpConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.io.File

private val logger = KotlinLogging.logger {}

@Factory
class McpConfigFactory {
    @Singleton
    fun mcpConfig(): McpConfig {
        val configFile = File("${KlawPaths.config}/mcp.json")
        if (!configFile.exists()) {
            logger.debug { "mcp.json not found at ${configFile.absolutePath}, MCP disabled" }
            return McpConfig()
        }
        return try {
            parseMcpConfig(configFile.readText())
        } catch (e: kotlinx.serialization.SerializationException) {
            logger.warn { "failed to parse mcp.json: ${e::class.simpleName}" }
            McpConfig()
        } catch (e: IllegalArgumentException) {
            logger.warn { "invalid mcp.json: ${e::class.simpleName}" }
            McpConfig()
        }
    }
}
