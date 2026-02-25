package io.github.klaw.common.config

import kotlinx.serialization.Serializable

@Serializable
data class GatewayConfig(
    val channels: ChannelsConfig,
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
)

@Serializable
data class DiscordConfig(
    val enabled: Boolean = false,
    val token: String? = null,
)
