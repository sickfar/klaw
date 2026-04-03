package io.github.klaw.cli.configure

import io.github.klaw.cli.init.ConfigTemplates
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiscordSectionHandlerTest {
    private fun engineConfig() = parseEngineConfig(ConfigTemplates.engineJson("anthropic/claude-sonnet-4-6"))

    private fun state(
        discordEnabled: Boolean = false,
        guildIds: List<String> = emptyList(),
    ) = ConfigState(
        engineConfig = engineConfig(),
        gatewayConfig =
            parseGatewayConfig(
                ConfigTemplates.gatewayJson(
                    telegramEnabled = false,
                    discordEnabled = discordEnabled,
                    discordAllowedGuilds = guildIds,
                ),
            ),
        envVars = mutableMapOf("KLAW_DISCORD_TOKEN" to "old-discord-token"),
    )

    @Test
    fun `enable discord with token and guild ids`() {
        val state = state(discordEnabled = false)
        val handler =
            DiscordSectionHandler(
                readLine = inputSequence("y", "new-discord-token", "guild1,guild2"),
                printer = { },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        val dc =
            state.gatewayConfig.channels.discord.values
                .firstOrNull()
        assertTrue(dc != null)
        assertEquals(listOf("guild1", "guild2"), dc.allowedGuilds.map { it.guildId })
        assertEquals("new-discord-token", state.envVars["KLAW_DISCORD_TOKEN"])
    }

    @Test
    fun `disable discord`() {
        val state = state(discordEnabled = true)
        val handler =
            DiscordSectionHandler(
                readLine = inputSequence("n"),
                printer = { },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        assertTrue(
            state.gatewayConfig.channels.discord
                .isEmpty(),
        )
    }

    @Test
    fun `keep existing token when empty`() {
        val state = state(discordEnabled = true)
        val handler =
            DiscordSectionHandler(
                readLine = inputSequence("y", "", "guild3"),
                printer = { },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        assertEquals("old-discord-token", state.envVars["KLAW_DISCORD_TOKEN"])
    }

    @Test
    fun `cancel returns false`() {
        val state = state()
        val handler =
            DiscordSectionHandler(
                readLine = { null },
                printer = { },
            )
        assertFalse(handler.run(state))
    }
}
