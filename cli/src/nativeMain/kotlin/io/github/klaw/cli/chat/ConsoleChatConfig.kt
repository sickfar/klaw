package io.github.klaw.cli.chat

import io.github.klaw.cli.util.readFileText

internal data class ConsoleChatConfig(
    val enabled: Boolean,
    val port: Int = 37474,
) {
    val wsUrl: String get() = "ws://localhost:$port/chat"
}

/**
 * Reads console chat configuration from gateway.yaml.
 * kaml is JVM-only — uses line-by-line parsing in native CLI.
 */
internal fun readConsoleChatConfig(configDir: String): ConsoleChatConfig {
    val text = readFileText("$configDir/gateway.yaml") ?: return ConsoleChatConfig(enabled = false)
    return parseConsoleChatConfig(text)
}

/**
 * Parses console chat configuration from gateway.yaml content using a simple state machine.
 * Extracts the `channels.console` section.
 */
internal fun parseConsoleChatConfig(text: String): ConsoleChatConfig {
    var inChannels = false
    var inConsole = false
    var enabled = false
    var port = 37474
    for (line in text.lines()) {
        if (line.isBlank()) continue
        val trimmed = line.trimStart()
        val indent = line.length - trimmed.length
        when {
            indent == 0 && trimmed.startsWith("channels:") -> {
                inChannels = true
                inConsole = false
            }

            indent == 0 && !trimmed.startsWith("channels:") -> {
                inChannels = false
                inConsole = false
            }

            inChannels && indent == 2 && trimmed.startsWith("console:") -> {
                inConsole = true
            }

            inChannels && indent == 2 && !trimmed.startsWith("console:") -> {
                inConsole = false
            }

            inConsole && trimmed.startsWith("enabled:") -> {
                enabled = trimmed.removePrefix("enabled:").trim() == "true"
            }

            inConsole && trimmed.startsWith("port:") -> {
                port = trimmed.removePrefix("port:").trim().toIntOrNull() ?: 37474
            }
        }
    }
    return ConsoleChatConfig(enabled = enabled, port = port)
}
