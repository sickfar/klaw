package io.github.klaw.gateway

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.ConsoleConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.channel.ChatWebSocketEndpoint
import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.ApplicationConfiguration
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ServiceKeepAliveTest {
    private val appContext = mockk<ApplicationContext>(relaxed = true)
    private val appConfig = mockk<ApplicationConfiguration>(relaxed = true)
    private val chatEndpoint = mockk<ChatWebSocketEndpoint>(relaxed = true)

    private fun makeConfig(
        enabled: Boolean = true,
        port: Int = 0,
    ): GatewayConfig = GatewayConfig(ChannelsConfig(console = ConsoleConfig(enabled = enabled, port = port)))

    private fun configNoConsole(): GatewayConfig = GatewayConfig(ChannelsConfig())

    @Test
    fun `isServer returns true`() {
        val keepAlive = ServiceKeepAlive(appContext, appConfig, chatEndpoint, makeConfig())
        assertTrue(keepAlive.isServer)
    }

    @Test
    fun `isRunning is false before start`() {
        val keepAlive = ServiceKeepAlive(appContext, appConfig, chatEndpoint, makeConfig())
        assertFalse(keepAlive.isRunning)
    }

    @Test
    fun `start sets running to true`() {
        val keepAlive = ServiceKeepAlive(appContext, appConfig, chatEndpoint, makeConfig())
        keepAlive.start()
        assertTrue(keepAlive.isRunning)
        keepAlive.stop()
    }

    @Test
    fun `stop sets running to false`() {
        val keepAlive = ServiceKeepAlive(appContext, appConfig, chatEndpoint, makeConfig())
        keepAlive.start()
        keepAlive.stop()
        assertFalse(keepAlive.isRunning)
    }

    @Test
    fun `start with console disabled does not throw`() {
        val keepAlive = ServiceKeepAlive(appContext, appConfig, chatEndpoint, makeConfig(enabled = false))
        keepAlive.start()
        assertTrue(keepAlive.isRunning)
        keepAlive.stop()
    }

    @Test
    fun `start with no console config does not throw`() {
        val keepAlive = ServiceKeepAlive(appContext, appConfig, chatEndpoint, configNoConsole())
        keepAlive.start()
        assertTrue(keepAlive.isRunning)
        keepAlive.stop()
    }

    @Test
    fun `start with console enabled starts Ktor server on specified port`() {
        val keepAlive = ServiceKeepAlive(appContext, appConfig, chatEndpoint, makeConfig(enabled = true, port = 0))
        keepAlive.start()
        assertTrue(keepAlive.isRunning)
        keepAlive.stop()
    }

    @Test
    fun `stop without start does not throw`() {
        val keepAlive = ServiceKeepAlive(appContext, appConfig, chatEndpoint, makeConfig())
        keepAlive.stop()
        assertFalse(keepAlive.isRunning)
    }
}
