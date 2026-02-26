package io.github.klaw.common.config

import kotlinx.serialization.Serializable

@Serializable
data class GatewayConfig(
    val channels: ChannelsConfig,
    val commands: List<CommandConfig> = emptyList(),
)

@Serializable
data class ChannelsConfig(
    val telegram: TelegramConfig? = null,
    val discord: DiscordConfig? = null,
)

@Serializable
data class TelegramConfig(
    val token: String,
    val allowedChatIds: List<String> = emptyList(),
) {
    override fun toString(): String = "TelegramConfig(token=***, allowedChatIds=$allowedChatIds)"
}

@Serializable
data class DiscordConfig(
    val enabled: Boolean = false,
    val token: String? = null,
) {
    override fun toString(): String = "DiscordConfig(enabled=$enabled, token=${if (token != null) "***" else "null"})"
}
