package io.github.klaw.cli.chat

import io.github.klaw.cli.util.readFileText
import io.github.klaw.common.config.parseGatewayConfig

internal data class ConsoleChatConfig(
    val enabled: Boolean,
    val port: Int = 37474,
    val apiToken: String = "",
) {
    val wsUrl: String
        get() {
            val base = "ws://localhost:$port/ws/chat"
            return if (apiToken.isNotBlank()) "$base?token=$apiToken" else base
        }

    val httpBaseUrl: String
        get() = "http://localhost:$port"
}

private val envVarPattern = Regex("""\$\{([A-Z_][A-Z0-9_]*)\}""")

private fun stripQuotes(value: String): String {
    if (value.length < 2) return value
    val first = value.first()
    val last = value.last()
    val isDoubleQuoted = first == '"' && last == '"'
    val isSingleQuoted = first == '\'' && last == '\''
    return if (isDoubleQuoted || isSingleQuoted) value.substring(1, value.length - 1) else value
}

/**
 * Resolves a raw config value against a .env file content.
 * If [raw] matches `${VAR_NAME}`, looks up VAR_NAME in [dotenvContent].
 * Returns the resolved value, empty string if var not found, or [raw] unchanged if not a pattern.
 */
internal fun resolveFromDotenv(
    raw: String,
    dotenvContent: String?,
): String {
    if (dotenvContent == null) return raw
    val match = envVarPattern.matchEntire(raw) ?: return raw
    val varName = match.groupValues[1]
    return dotenvContent
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith('#') }
        .mapNotNull { line ->
            val eqIdx = line.indexOf('=')
            if (eqIdx > 0) line.substring(0, eqIdx) to line.substring(eqIdx + 1) else null
        }.firstOrNull { it.first == varName }
        ?.second
        ?.let { stripQuotes(it) }
        ?: ""
}

/**
 * Reads local WebSocket chat configuration from gateway.json.
 * Uses common parseGatewayConfig (kotlinx-serialization-json, available on all targets).
 * Resolves apiToken from webui config, with .env variable substitution.
 */
internal fun readConsoleChatConfig(configDir: String): ConsoleChatConfig {
    val text = readFileText("$configDir/gateway.json") ?: return ConsoleChatConfig(enabled = false)
    return try {
        val config = parseGatewayConfig(text)
        val localWs = config.channels.localWs
        if (localWs != null) {
            val rawToken = config.webui.apiToken
            val dotenvContent = readFileText("$configDir/.env")
            val resolvedToken = resolveFromDotenv(rawToken, dotenvContent)
            ConsoleChatConfig(enabled = localWs.enabled, port = localWs.port, apiToken = resolvedToken)
        } else {
            ConsoleChatConfig(enabled = false)
        }
    } catch (_: Exception) {
        ConsoleChatConfig(enabled = false)
    }
}
