package io.github.klaw.cli.configure

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.TelegramChannelConfig

internal class TelegramSectionHandler(
    private val readLine: () -> String?,
    private val printer: (String) -> Unit,
) : SectionHandler {
    override val section: ConfigSection = ConfigSection.TELEGRAM

    override fun run(state: ConfigState): Boolean {
        val current =
            state.gatewayConfig.channels.telegram.values
                .firstOrNull()
        val currentEnabled = current != null
        val currentChatIds = current?.allowedChats?.map { it.chatId } ?: emptyList()

        printCurrentState(currentEnabled, currentChatIds)

        val enable = promptEnable(currentEnabled) ?: return false

        if (!enable) return handleDisable(state, currentEnabled)

        if (!promptAndApplyToken(state)) return false

        val chatIds = promptChatIds(currentChatIds) ?: return false
        val existingChats = current?.allowedChats ?: emptyList()

        applyConfig(state, chatIds, existingChats)
        return true
    }

    private fun printCurrentState(
        enabled: Boolean,
        chatIds: List<String>,
    ) {
        printer("\n── Telegram ──")
        printer("Current: ${if (enabled) "enabled" else "disabled"}")
        if (chatIds.isNotEmpty()) {
            printer("Allowed chats: ${chatIds.joinToString(", ")}")
        }
    }

    private fun promptEnable(currentEnabled: Boolean): Boolean? {
        printer("Configure Telegram bot? [${if (currentEnabled) "Y/n" else "y/N"}]:")
        val input = readLine() ?: return null
        return when {
            input.isBlank() -> currentEnabled
            input.lowercase().startsWith("y") -> true
            else -> false
        }
    }

    private fun handleDisable(
        state: ConfigState,
        wasEnabled: Boolean,
    ): Boolean {
        if (wasEnabled) {
            state.gatewayConfig =
                state.gatewayConfig.copy(
                    channels = state.gatewayConfig.channels.copy(telegram = emptyMap()),
                )
            state.envVars.remove("KLAW_TELEGRAM_TOKEN")
            return true
        }
        return false
    }

    private fun promptAndApplyToken(state: ConfigState): Boolean {
        val currentToken = state.envVars["KLAW_TELEGRAM_TOKEN"]
        val tokenHint = if (currentToken != null) " [keep current]" else ""
        printer("Telegram bot token$tokenHint:")
        val tokenInput = readLine() ?: return false
        val token = tokenInput.ifBlank { currentToken ?: "" }
        if (token.isNotBlank()) {
            state.envVars["KLAW_TELEGRAM_TOKEN"] = token
        }
        return true
    }

    private fun promptChatIds(currentChatIds: List<String>): List<String>? {
        val hint = if (currentChatIds.isNotEmpty()) " [${currentChatIds.joinToString(",")}]" else ""
        printer("Allowed chat IDs (comma-separated, empty to keep current)$hint:")
        val input = readLine() ?: return null
        return if (input.isBlank()) {
            currentChatIds
        } else {
            input.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    private fun applyConfig(
        state: ConfigState,
        chatIds: List<String>,
        existingChats: List<AllowedChat>,
    ) {
        val existingByChat = existingChats.associateBy { it.chatId }
        val allowedChats =
            chatIds.map { id ->
                existingByChat[id] ?: AllowedChat(chatId = id)
            }
        state.gatewayConfig =
            state.gatewayConfig.copy(
                channels =
                    state.gatewayConfig.channels.copy(
                        telegram =
                            mapOf(
                                "default" to
                                    TelegramChannelConfig(
                                        agentId = "default",
                                        token = "\${KLAW_TELEGRAM_TOKEN}",
                                        allowedChats = allowedChats,
                                    ),
                            ),
                    ),
            )
    }
}
