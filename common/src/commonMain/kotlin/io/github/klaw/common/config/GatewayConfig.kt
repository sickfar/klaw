package io.github.klaw.common.config

import kotlinx.serialization.Serializable

@Serializable
data class GatewayConfig(
    @ConfigDoc("Channel transport configurations")
    val channels: ChannelsConfig = ChannelsConfig(),
    @ConfigDoc("Delivery reliability settings")
    val delivery: DeliveryConfig = DeliveryConfig(),
    @ConfigDoc("Attachment handling settings")
    val attachments: AttachmentsConfig = AttachmentsConfig(),
    @ConfigDoc("Web UI settings")
    val webui: WebuiConfig = WebuiConfig(),
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
    @ConfigDoc("Telegram bot channel instances keyed by name")
    val telegram: Map<String, TelegramChannelConfig> = emptyMap(),
    @ConfigDoc("Discord bot channel instances keyed by name")
    val discord: Map<String, DiscordChannelConfig> = emptyMap(),
    @ConfigDoc("WebSocket channel instances keyed by name")
    val websocket: Map<String, WebSocketChannelConfig> = emptyMap(),
)

@Serializable
data class TelegramChannelConfig(
    @ConfigDoc("Agent ID this channel routes messages to")
    val agentId: String,
    @ConfigDoc("Telegram Bot API token", sensitive = true)
    val token: String,
    @ConfigDoc("List of chats allowed to interact with the bot")
    val allowedChats: List<AllowedChat> = emptyList(),
    @ConfigDoc("Custom API base URL (testing only)")
    val apiBaseUrl: String? = null,
) {
    override fun toString(): String = "TelegramChannelConfig(agentId=$agentId, token=***, allowedChats=$allowedChats)"
}

@Serializable
data class DiscordChannelConfig(
    @ConfigDoc("Agent ID this channel routes messages to")
    val agentId: String,
    @ConfigDoc("Discord bot token", sensitive = true)
    val token: String,
    @ConfigDoc("List of guilds (servers) allowed to interact with the bot")
    val allowedGuilds: List<AllowedGuild> = emptyList(),
    @ConfigDoc("Custom API base URL (testing only)")
    val apiBaseUrl: String? = null,
) {
    override fun toString(): String = "DiscordChannelConfig(agentId=$agentId, token=***, allowedGuilds=$allowedGuilds)"
}

@Serializable
data class WebSocketChannelConfig(
    @ConfigDoc("Agent ID this channel routes messages to")
    val agentId: String,
    @ConfigDoc("TCP port for the WebSocket channel")
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
data class AllowedGuild(
    @ConfigDoc("Discord guild (server) ID")
    val guildId: String,
    @ConfigDoc("Allowed channel IDs within guild (empty = all channels)")
    val allowedChannelIds: List<String> = emptyList(),
    @ConfigDoc("Allowed user IDs (empty = deny all)")
    val allowedUserIds: List<String> = emptyList(),
)

@Serializable
data class WebuiConfig(
    @ConfigDoc("Enable the Web UI (REST API + SPA)")
    val enabled: Boolean = true,
    @ConfigDoc("Bearer token for API authentication (supports \${ENV_VAR} substitution, empty = no auth)")
    val apiToken: String = "",
)

@Serializable
data class AttachmentsConfig(
    @ConfigDoc("Directory for storing received image attachments (empty = disabled)")
    val directory: String = "",
)
