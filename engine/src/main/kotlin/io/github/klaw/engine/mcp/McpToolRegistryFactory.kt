package io.github.klaw.engine.mcp

import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class McpToolRegistryFactory {
    @Singleton
    fun mcpToolRegistry(): McpToolRegistry = McpToolRegistry()
}
