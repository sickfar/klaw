package io.github.klaw.cli.configure

import io.github.klaw.common.config.AllowedGuild
import io.github.klaw.common.config.DiscordChannelConfig

internal class DiscordSectionHandler(
    private val readLine: () -> String?,
    private val printer: (String) -> Unit,
) : SectionHandler {
    override val section: ConfigSection = ConfigSection.DISCORD

    override fun run(state: ConfigState): Boolean {
        val current =
            state.gatewayConfig.channels.discord.values
                .firstOrNull()
        val currentEnabled = current != null
        val currentGuilds = current?.allowedGuilds?.map { it.guildId } ?: emptyList()

        printCurrentState(currentEnabled, currentGuilds)

        val enable = promptEnable(currentEnabled) ?: return false

        if (!enable) return handleDisable(state, currentEnabled)

        if (!promptAndApplyToken(state)) return false

        val guildIds = promptGuildIds(currentGuilds) ?: return false
        val existingGuilds = current?.allowedGuilds ?: emptyList()

        applyConfig(state, guildIds, existingGuilds)
        return true
    }

    private fun printCurrentState(
        enabled: Boolean,
        guilds: List<String>,
    ) {
        printer("\n── Discord ──")
        printer("Current: ${if (enabled) "enabled" else "disabled"}")
        if (guilds.isNotEmpty()) {
            printer("Allowed guilds: ${guilds.joinToString(", ")}")
        }
    }

    private fun promptEnable(currentEnabled: Boolean): Boolean? {
        printer("Configure Discord bot? [${if (currentEnabled) "Y/n" else "y/N"}]:")
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
                    channels = state.gatewayConfig.channels.copy(discord = emptyMap()),
                )
            state.envVars.remove("KLAW_DISCORD_TOKEN")
            return true
        }
        return false
    }

    private fun promptAndApplyToken(state: ConfigState): Boolean {
        val currentToken = state.envVars["KLAW_DISCORD_TOKEN"]
        val tokenHint = if (currentToken != null) " [keep current]" else ""
        printer("Discord bot token$tokenHint:")
        val tokenInput = readLine() ?: return false
        val token = tokenInput.ifBlank { currentToken ?: "" }
        if (token.isNotBlank()) {
            state.envVars["KLAW_DISCORD_TOKEN"] = token
        }
        return true
    }

    private fun promptGuildIds(currentGuilds: List<String>): List<String>? {
        val hint = if (currentGuilds.isNotEmpty()) " [${currentGuilds.joinToString(",")}]" else ""
        printer("Allowed guild IDs (comma-separated, empty to keep current)$hint:")
        val input = readLine() ?: return null
        return if (input.isBlank()) {
            currentGuilds
        } else {
            input.split(",").map { it.trim() }.filter { it.isNotBlank() }
        }
    }

    private fun applyConfig(
        state: ConfigState,
        guildIds: List<String>,
        existingGuilds: List<AllowedGuild>,
    ) {
        val existingByGuild = existingGuilds.associateBy { it.guildId }
        val allowedGuilds =
            guildIds.map { id ->
                existingByGuild[id] ?: AllowedGuild(guildId = id)
            }
        state.gatewayConfig =
            state.gatewayConfig.copy(
                channels =
                    state.gatewayConfig.channels.copy(
                        discord =
                            mapOf(
                                "default" to
                                    DiscordChannelConfig(
                                        agentId = "default",
                                        token = "\${KLAW_DISCORD_TOKEN}",
                                        allowedGuilds = allowedGuilds,
                                    ),
                            ),
                    ),
            )
    }
}
