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

    @Suppress("ReturnCount")
    fun isAllowed(
        channel: String,
        chatId: String,
        userId: String?,
    ): Boolean {
        if (channel == "console") {
            logger.trace { "console channel always allowed" }
            return true
        }
        val allowedChats =
            when (channel) {
                "telegram" -> {
                    config.channels.telegram?.allowedChats ?: run {
                        logger.trace { "telegram config absent, denied chatId=$chatId" }
                        return false
                    }
                }

                else -> {
                    logger.trace { "unknown channel=$channel, denied" }
                    return false
                }
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

    @Suppress("ReturnCount")
    fun isStartAllowed(
        channel: String,
        chatId: String,
        userId: String?,
    ): PairingStatus {
        if (channel == "console") return PairingStatus.AlreadyPaired
        val allowedChats =
            when (channel) {
                "telegram" -> config.channels.telegram?.allowedChats ?: emptyList()
                else -> emptyList()
            }
        val chat =
            allowedChats.find { it.chatId == chatId }
                ?: return PairingStatus.NewChat
        if (chat.allowedUserIds.isEmpty()) return PairingStatus.NewUserInExistingChat
        if (userId == null || userId !in chat.allowedUserIds) return PairingStatus.NewUserInExistingChat
        return PairingStatus.AlreadyPaired
    }

    @Suppress("ReturnCount")
    fun isChatAllowed(
        channel: String,
        chatId: String,
    ): Boolean {
        if (channel == "console") return chatId == "console_default"
        val allowedChats =
            when (channel) {
                "telegram" -> config.channels.telegram?.allowedChats ?: return false
                else -> return false
            }
        return allowedChats.any { it.chatId == chatId }
    }

    fun reload(newConfig: GatewayConfig) {
        logger.trace { "reloading allowlist config" }
        config = newConfig
    }
}
