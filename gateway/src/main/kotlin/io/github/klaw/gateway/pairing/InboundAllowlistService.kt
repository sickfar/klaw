package io.github.klaw.gateway.pairing

import io.github.klaw.common.config.GatewayConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

enum class PairingStatus {
    AlreadyPaired,
    NewChat,
    NewUserInExistingChat,
}

@Singleton
class InboundAllowlistService(
    config: GatewayConfig,
) {
    @Volatile
    private var config: GatewayConfig = config

    fun isAllowed(
        channel: String,
        chatId: String,
        userId: String?,
    ): Boolean {
        if (isWebSocketChannel(channel)) {
            logger.trace { "websocket channel=$channel always allowed" }
            return true
        }
        return when (resolveChannelType(channel)) {
            "telegram" -> {
                isAllowedTelegram(chatId, userId)
            }

            "discord" -> {
                isAllowedDiscord(chatId, userId)
            }

            else -> {
                logger.trace { "unknown channel=$channel, denied" }
                false
            }
        }
    }

    private fun isWebSocketChannel(channel: String): Boolean =
        channel == "local_ws" || config.channels.websocket.containsKey(channel)

    private fun resolveChannelType(channel: String): String =
        when {
            config.channels.telegram.containsKey(channel) -> "telegram"
            config.channels.discord.containsKey(channel) -> "discord"
            config.channels.websocket.containsKey(channel) -> "websocket"
            else -> channel // fallback to raw name for backward compat
        }

    private fun isAllowedTelegram(
        chatId: String,
        userId: String?,
    ): Boolean {
        val telegramConfig =
            config.channels.telegram.values
                .firstOrNull()
        val allowedChats =
            telegramConfig?.allowedChats ?: run {
                logger.trace { "telegram config absent, denied chatId=$chatId" }
                return false
            }
        val chat = allowedChats.find { it.chatId == chatId }
        if (chat == null) {
            logger.trace { "chatId=$chatId not in allowlist, denied" }
            return false
        }
        if (chat.allowedUserIds.isEmpty()) {
            logger.trace { "chatId=$chatId has empty allowedUserIds, denied" }
            return false
        }
        val allowed = userId != null && userId in chat.allowedUserIds
        logger.trace { "chatId=$chatId userId=$userId allowed=$allowed" }
        return allowed
    }

    private fun isAllowedDiscord(
        chatId: String,
        userId: String?,
    ): Boolean {
        val discordConfig =
            config.channels.discord.values
                .firstOrNull()
        val guilds =
            discordConfig?.allowedGuilds ?: run {
                logger.trace { "discord config absent, denied chatId=$chatId" }
                return false
            }
        val allowed =
            guilds.any { guild ->
                guild.allowedUserIds.isNotEmpty() && userId != null && userId in guild.allowedUserIds
            }
        logger.trace { "discord allowlist check chatId=$chatId userId=$userId allowed=$allowed" }
        return allowed
    }

    fun isStartAllowed(
        channel: String,
        chatId: String,
        userId: String?,
    ): PairingStatus =
        when {
            isWebSocketChannel(channel) -> PairingStatus.AlreadyPaired
            resolveChannelType(channel) == "discord" -> isStartAllowedDiscord(userId)
            else -> isStartAllowedChat(channel, chatId, userId)
        }

    private fun isStartAllowedDiscord(userId: String?): PairingStatus {
        val guilds =
            config.channels.discord.values
                .firstOrNull()
                ?.allowedGuilds
        if (guilds.isNullOrEmpty()) return PairingStatus.NewChat
        val hasUser = guilds.any { g -> userId != null && userId in g.allowedUserIds }
        return if (hasUser) PairingStatus.AlreadyPaired else PairingStatus.NewUserInExistingChat
    }

    private fun isStartAllowedChat(
        channel: String,
        chatId: String,
        userId: String?,
    ): PairingStatus {
        val allowedChats =
            when (channel) {
                "telegram" -> {
                    config.channels.telegram.values
                        .firstOrNull()
                        ?.allowedChats ?: emptyList()
                }

                else -> {
                    emptyList()
                }
            }
        val chat = allowedChats.find { it.chatId == chatId } ?: return PairingStatus.NewChat
        if (chat.allowedUserIds.isEmpty()) return PairingStatus.NewUserInExistingChat
        return if (userId != null && userId in chat.allowedUserIds) {
            PairingStatus.AlreadyPaired
        } else {
            PairingStatus.NewUserInExistingChat
        }
    }

    fun isChatAllowed(
        channel: String,
        chatId: String,
    ): Boolean =
        when {
            isWebSocketChannel(channel) -> true
            resolveChannelType(channel) == "telegram" -> isChatAllowedTelegram(chatId)
            resolveChannelType(channel) == "discord" -> isChatAllowedDiscord()
            else -> false
        }

    private fun isChatAllowedTelegram(chatId: String): Boolean {
        val allowedChats =
            config.channels.telegram.values
                .firstOrNull()
                ?.allowedChats ?: return false
        return allowedChats.any { it.chatId == chatId }
    }

    private fun isChatAllowedDiscord(): Boolean {
        val guilds =
            config.channels.discord.values
                .firstOrNull()
                ?.allowedGuilds ?: return false
        return guilds.isNotEmpty()
    }

    fun reload(newConfig: GatewayConfig) {
        logger.trace { "reloading allowlist config" }
        config = newConfig
    }
}
