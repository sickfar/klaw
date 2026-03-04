package io.github.klaw.common.config

import kotlinx.serialization.Serializable

@Serializable
data class GatewayConfig(
    @ConfigDoc("Channel transport configurations")
    val channels: ChannelsConfig,
    @ConfigDoc("Custom slash commands available to gateway users")
    val commands: List<CommandConfig> = emptyList(),
)

@Serializable
data class ChannelsConfig(
    @ConfigDoc("Telegram bot channel settings")
    val telegram: TelegramConfig? = null,
    @ConfigDoc("Discord bot channel settings")
    val discord: DiscordConfig? = null,
    @ConfigDoc("Console debug channel settings")
    val console: ConsoleConfig? = null,
)

@Serializable
data class ConsoleConfig(
    @ConfigDoc("Enable the console debug channel")
    val enabled: Boolean = false,
    @ConfigDoc("TCP port for the console debug channel")
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
