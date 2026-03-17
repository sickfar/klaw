package io.github.klaw.common.config

import kotlinx.serialization.Serializable

@Serializable
data class GatewayConfig(
    @ConfigDoc("Channel transport configurations")
    val channels: ChannelsConfig,
    @ConfigDoc("Custom slash commands available to gateway users")
    val commands: List<CommandConfig> = emptyList(),
    @ConfigDoc("Delivery reliability settings")
    val delivery: DeliveryConfig = DeliveryConfig(),
)

@Serializable
data class DeliveryConfig(
    @ConfigDoc("Max consecutive reconnect failures before giving up (0 = unlimited)")
    val maxReconnectAttempts: Int = 0,
    @ConfigDoc("Max seconds for draining inbound buffer on reconnect (0 = unlimited)")
    val drainBudgetSeconds: Int = 30,
    @ConfigDoc("Max seconds for draining per-channel buffer (0 = unlimited)")
    val channelDrainBudgetSeconds: Int = 30,
) {
    init {
        require(maxReconnectAttempts >= 0) { "maxReconnectAttempts must be non-negative" }
        require(drainBudgetSeconds >= 0) { "drainBudgetSeconds must be non-negative" }
        require(channelDrainBudgetSeconds >= 0) { "channelDrainBudgetSeconds must be non-negative" }
    }
}

@Serializable
data class ChannelsConfig(
    @ConfigDoc("Telegram bot channel settings")
    val telegram: TelegramConfig? = null,
    @ConfigDoc("Discord bot channel settings")
    val discord: DiscordConfig? = null,
    @ConfigDoc("Local WebSocket channel settings")
    val localWs: LocalWsConfig? = null,
)

@Serializable
data class LocalWsConfig(
    @ConfigDoc("Enable the local WebSocket channel")
    val enabled: Boolean = false,
    @ConfigDoc("TCP port for the local WebSocket channel")
    val port: Int = 37474,
)

@Serializable
data class AllowedChat(
    @ConfigDoc("Platform-specific chat identifier")
    val chatId: String,
    @ConfigDoc("List of user IDs allowed to interact in this chat")
    val allowedUserIds: List<String> = emptyList(),
)

@Serializable
data class TelegramConfig(
    @ConfigDoc("Telegram Bot API token", sensitive = true)
    val token: String,
    @ConfigDoc("List of chats allowed to interact with the bot")
    val allowedChats: List<AllowedChat> = emptyList(),
) {
    override fun toString(): String = "TelegramConfig(token=***, allowedChats=$allowedChats)"
}

@Serializable
data class DiscordConfig(
    @ConfigDoc("Enable the Discord bot channel")
    val enabled: Boolean = false,
    @ConfigDoc("Discord bot token", sensitive = true)
    val token: String? = null,
) {
    override fun toString(): String = "DiscordConfig(enabled=$enabled, token=${if (token != null) "***" else "null"})"
}
