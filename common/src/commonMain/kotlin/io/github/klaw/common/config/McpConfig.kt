package io.github.klaw.common.config

import kotlinx.serialization.Serializable

@Serializable
data class McpConfig(
    @ConfigDoc("MCP server definitions keyed by server name")
    val servers: Map<String, McpServerConfig> = emptyMap(),
) {
    init {
        val invalidNames = servers.keys.filter { !SERVER_NAME_PATTERN.matches(it) || it.contains("__") }
        require(invalidNames.isEmpty()) {
            "Invalid MCP server name(s): $invalidNames. " +
                "Names must match [a-zA-Z0-9][a-zA-Z0-9_-]* and must not contain '__'."
        }
    }

    companion object {
        private val SERVER_NAME_PATTERN = Regex("^[a-zA-Z0-9][a-zA-Z0-9_-]*$")
    }
}

@Serializable
data class McpServerConfig(
    @ConfigDoc("Enable or disable this MCP server")
    val enabled: Boolean = true,
    @ConfigDoc("Transport type", possibleValues = ["stdio", "http"])
    val transport: String,
    @ConfigDoc("Command to spawn (stdio only)")
    val command: String? = null,
    @ConfigDoc("Command arguments (stdio only)")
    val args: List<String> = emptyList(),
    @ConfigDoc("Extra environment variables (stdio only)")
    val env: Map<String, String> = emptyMap(),
    @ConfigDoc("HTTP endpoint URL (http only)")
    val url: String? = null,
    @ConfigDoc("Bearer token for HTTP auth (supports \${VAR} from .env)", sensitive = true)
    val apiKey: String? = null,
    @ConfigDoc("Per-call timeout in milliseconds")
    val timeoutMs: Long = 30_000,
    @ConfigDoc("Reconnect delay in milliseconds (stdio only)")
    val reconnectDelayMs: Long = 5_000,
    @ConfigDoc("Maximum reconnect attempts, 0 = infinite (stdio only)")
    val maxReconnectAttempts: Int = 0,
) {
    override fun toString(): String =
        "McpServerConfig(enabled=$enabled, transport=$transport, " +
            "command=$command, url=$url, apiKey=${if (apiKey != null) "***" else "null"})"
}
