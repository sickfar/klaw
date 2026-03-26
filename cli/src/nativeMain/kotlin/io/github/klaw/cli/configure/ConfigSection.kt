package io.github.klaw.cli.configure

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.GatewayConfig

internal enum class ConfigSection(
    val cliName: String,
    val label: String,
) {
    MODEL("model", "Model"),
    TELEGRAM("telegram", "Telegram"),
    DISCORD("discord", "Discord"),
    WEBSOCKET("websocket", "WebSocket"),
    WEB_SEARCH("web-search", "Web Search"),
    SERVICES("services", "Services"),
    ;

    companion object {
        fun fromCliName(name: String): ConfigSection? = entries.firstOrNull { it.cliName == name }
    }
}

internal data class ConfigState(
    var engineConfig: EngineConfig,
    var gatewayConfig: GatewayConfig,
    val envVars: MutableMap<String, String>,
)

internal interface SectionHandler {
    val section: ConfigSection

    fun run(state: ConfigState): Boolean
}
