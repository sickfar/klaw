package io.github.klaw.engine.mcp

interface McpTransport {
    suspend fun send(message: String)

    suspend fun receive(): String

    suspend fun close()

    val isOpen: Boolean
}
