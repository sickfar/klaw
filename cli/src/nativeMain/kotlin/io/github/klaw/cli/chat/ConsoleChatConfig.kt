package io.github.klaw.cli.chat

import io.github.klaw.cli.util.readFileText
import io.github.klaw.common.config.parseGatewayConfig

internal data class ConsoleChatConfig(
    val enabled: Boolean,
    val port: Int = 37474,
) {
    val wsUrl: String get() = "ws://localhost:$port/chat"
}

/**
 * Reads console chat configuration from gateway.json.
 * Uses common parseGatewayConfig (kotlinx-serialization-json, available on all targets).
 */
internal fun readConsoleChatConfig(configDir: String): ConsoleChatConfig {
    val text = readFileText("$configDir/gateway.json") ?: return ConsoleChatConfig(enabled = false)
    return try {
        val config = parseGatewayConfig(text)
        val console = config.channels.console
        if (console != null) {
            ConsoleChatConfig(enabled = console.enabled, port = console.port)
        } else {
            ConsoleChatConfig(enabled = false)
        }
    } catch (_: Exception) {
        ConsoleChatConfig(enabled = false)
    }
}
