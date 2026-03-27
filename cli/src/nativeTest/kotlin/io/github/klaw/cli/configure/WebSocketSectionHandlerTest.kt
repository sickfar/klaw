package io.github.klaw.cli.configure

import io.github.klaw.cli.init.ConfigTemplates
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WebSocketSectionHandlerTest {
    private fun engineConfig() = parseEngineConfig(ConfigTemplates.engineJson("anthropic/claude-sonnet-4-6"))

    private fun gatewayConfig(
        wsEnabled: Boolean = false,
        wsPort: Int = 37474,
    ) = parseGatewayConfig(
        ConfigTemplates.gatewayJson(
            telegramEnabled = false,
            enableLocalWs = wsEnabled,
            localWsPort = wsPort,
        ),
    )

    private fun state(
        wsEnabled: Boolean = false,
        wsPort: Int = 37474,
    ) = ConfigState(
        engineConfig = engineConfig(),
        gatewayConfig = gatewayConfig(wsEnabled, wsPort),
        envVars = mutableMapOf(),
    )

    @Test
    fun `enable websocket`() {
        val state = state(wsEnabled = false)
        val output = mutableListOf<String>()
        val handler =
            WebSocketSectionHandler(
                readLine = inputSequence("y", "8080"),
                printer = { output.add(it) },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        val ws = state.gatewayConfig.channels.localWs
        assertTrue(ws != null && ws.enabled)
        assertEquals(8080, ws.port)
    }

    @Test
    fun `disable websocket`() {
        val state = state(wsEnabled = true, wsPort = 8080)
        val output = mutableListOf<String>()
        val handler =
            WebSocketSectionHandler(
                readLine = inputSequence("n"),
                printer = { output.add(it) },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        val ws = state.gatewayConfig.channels.localWs
        assertTrue(ws == null || !ws.enabled)
    }

    @Test
    fun `keep default port`() {
        val state = state(wsEnabled = false)
        val output = mutableListOf<String>()
        val handler =
            WebSocketSectionHandler(
                readLine = inputSequence("y", ""),
                printer = { output.add(it) },
            )
        val changed = handler.run(state)
        assertTrue(changed)
        assertEquals(
            37474,
            state.gatewayConfig.channels.localWs
                ?.port,
        )
    }

    @Test
    fun `cancel returns false`() {
        val state = state()
        val handler =
            WebSocketSectionHandler(
                readLine = { null },
                printer = { },
            )
        val changed = handler.run(state)
        assertFalse(changed)
    }
}

internal fun inputSequence(vararg inputs: String): () -> String? {
    val iterator = inputs.iterator()
    return { if (iterator.hasNext()) iterator.next() else null }
}
