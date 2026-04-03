package io.github.klaw.cli.configure

import io.github.klaw.cli.init.ConfigTemplates
import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TelegramSectionHandlerTest {
    private fun engineConfig() = parseEngineConfig(ConfigTemplates.engineJson("anthropic/claude-sonnet-4-6"))

    private fun state(
        telegramEnabled: Boolean = true,
        chatIds: List<String> = emptyList(),
    ) = ConfigState(
        engineConfig = engineConfig(),
        gatewayConfig =
            parseGatewayConfig(
                ConfigTemplates.gatewayJson(
                    telegramEnabled = telegramEnabled,
                    allowedChats = chatIds.map { AllowedChat(chatId = it) },
                ),
            ),
        envVars = mutableMapOf("KLAW_TELEGRAM_TOKEN" to "old-token"),
    )

    @Test
    fun `enable telegram with token and chat ids`() {
        val state = state(telegramEnabled = false)
        val handler =
            TelegramSectionHandler(
                readLine = inputSequence("y", "new-bot-token", "123,456"),
                printer = { },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        val tg =
            state.gatewayConfig.channels.telegram.values
                .firstOrNull()
        assertTrue(tg != null)
        assertEquals("\${KLAW_TELEGRAM_TOKEN}", tg.token)
        assertEquals(listOf("123", "456"), tg.allowedChats.map { it.chatId })
        assertEquals("new-bot-token", state.envVars["KLAW_TELEGRAM_TOKEN"])
    }

    @Test
    fun `disable telegram`() {
        val state = state(telegramEnabled = true)
        val handler =
            TelegramSectionHandler(
                readLine = inputSequence("n"),
                printer = { },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        assertTrue(
            state.gatewayConfig.channels.telegram
                .isEmpty(),
        )
    }

    @Test
    fun `keep existing token when empty input`() {
        val state = state(telegramEnabled = true)
        val handler =
            TelegramSectionHandler(
                readLine = inputSequence("y", "", "789"),
                printer = { },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        assertEquals("old-token", state.envVars["KLAW_TELEGRAM_TOKEN"])
    }

    @Test
    fun `empty chat ids keeps empty list`() {
        val state = state(telegramEnabled = false)
        val handler =
            TelegramSectionHandler(
                readLine = inputSequence("y", "tok", ""),
                printer = { },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        assertTrue(
            state.gatewayConfig.channels.telegram.values
                .firstOrNull()
                ?.allowedChats
                ?.isEmpty() == true,
        )
    }

    @Test
    fun `cancel returns false`() {
        val state = state()
        val handler =
            TelegramSectionHandler(
                readLine = { null },
                printer = { },
            )
        assertFalse(handler.run(state))
    }
}
