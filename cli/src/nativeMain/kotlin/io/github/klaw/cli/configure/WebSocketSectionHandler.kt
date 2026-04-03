package io.github.klaw.cli.configure

import io.github.klaw.common.config.WebSocketChannelConfig

private const val DEFAULT_WS_PORT = 37474

internal class WebSocketSectionHandler(
    private val readLine: () -> String?,
    private val printer: (String) -> Unit,
) : SectionHandler {
    override val section: ConfigSection = ConfigSection.WEBSOCKET

    override fun run(state: ConfigState): Boolean {
        val current =
            state.gatewayConfig.channels.websocket.values
                .firstOrNull()
        val currentEnabled = current != null
        val currentPort = current?.port ?: DEFAULT_WS_PORT

        printer("\n── WebSocket Chat ──")
        printer("Current: ${if (currentEnabled) "enabled (port $currentPort)" else "disabled"}")

        printer("Enable WebSocket chat? [${if (currentEnabled) "Y/n" else "y/N"}]:")
        val enableInput = readLine() ?: return false
        val enable =
            when {
                enableInput.isBlank() -> currentEnabled
                enableInput.lowercase().startsWith("y") -> true
                else -> false
            }

        if (!enable) {
            if (currentEnabled) {
                state.gatewayConfig =
                    state.gatewayConfig.copy(
                        channels = state.gatewayConfig.channels.copy(websocket = emptyMap()),
                    )
                return true
            }
            return false
        }

        printer("Port [$currentPort]:")
        val portInput = readLine() ?: return false
        val port = if (portInput.isBlank()) currentPort else portInput.toIntOrNull() ?: currentPort

        state.gatewayConfig =
            state.gatewayConfig.copy(
                channels =
                    state.gatewayConfig.channels.copy(
                        websocket =
                            mapOf("default" to WebSocketChannelConfig(agentId = "default", port = port)),
                    ),
            )
        return true
    }
}
