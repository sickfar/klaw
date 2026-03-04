package io.github.klaw.gateway

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.pairing.ConfigFileWatcher
import io.github.klaw.gateway.pairing.InboundAllowlistService
import io.github.klaw.gateway.pairing.PairingService
import io.github.klaw.gateway.socket.EngineSocketClient
import io.github.klaw.gateway.socket.GatewayOutboundHandler
import io.micronaut.context.event.StartupEvent
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test

class GatewayLifecycleStartupTest {
    private fun makeLifecycle(configFileWatcher: ConfigFileWatcher): GatewayLifecycle {
        val engineClient = mockk<EngineSocketClient>(relaxed = true)
        val outboundHandler = mockk<GatewayOutboundHandler>(relaxed = true)
        val allowlistService = mockk<InboundAllowlistService>(relaxed = true)
        val pairingService = mockk<PairingService>(relaxed = true)
        return GatewayLifecycle(
            channels = emptyList(),
            engineClient = engineClient,
            outboundHandler = outboundHandler,
            allowlistService = allowlistService,
            pairingService = pairingService,
            configFileWatcher = configFileWatcher,
        )
    }

    @Test
    fun `configFileWatcher is started during application startup`() {
        val configFileWatcher = mockk<ConfigFileWatcher>(relaxed = true)
        val lifecycle = makeLifecycle(configFileWatcher)

        lifecycle.onApplicationEvent(mockk<StartupEvent>(relaxed = true))
        lifecycle.stop()

        verify { configFileWatcher.startWatching(any()) }
    }

    @Test
    fun `watcher callback reloads allowlist service on config change`() {
        val allowlistService = mockk<InboundAllowlistService>(relaxed = true)
        val capturedCallback = slot<(GatewayConfig) -> Unit>()
        val configFileWatcher = mockk<ConfigFileWatcher>()
        every { configFileWatcher.startWatching(capture(capturedCallback)) } just runs
        every { configFileWatcher.stopWatching() } just runs

        val engineClient = mockk<EngineSocketClient>(relaxed = true)
        val outboundHandler = mockk<GatewayOutboundHandler>(relaxed = true)
        val pairingService = mockk<PairingService>(relaxed = true)

        val lifecycle =
            GatewayLifecycle(
                channels = emptyList<Channel>(),
                engineClient = engineClient,
                outboundHandler = outboundHandler,
                allowlistService = allowlistService,
                pairingService = pairingService,
                configFileWatcher = configFileWatcher,
            )
        lifecycle.onApplicationEvent(mockk<StartupEvent>(relaxed = true))
        lifecycle.stop()

        val newConfig = mockk<GatewayConfig>()
        capturedCallback.captured.invoke(newConfig)

        verify { allowlistService.reload(newConfig) }
    }
}
