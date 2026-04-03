package io.github.klaw.gateway.socket

import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.DeliveryConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramChannelConfig
import io.micronaut.context.ApplicationContext
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class GatewaySocketFactoryTest {
    @TempDir
    lateinit var tempDir: Path

    private fun makeConfig(maxReconnectAttempts: Int): GatewayConfig =
        GatewayConfig(
            channels =
                ChannelsConfig(
                    telegram =
                        mapOf(
                            "default" to
                                TelegramChannelConfig(agentId = "default", token = "tok", allowedChats = emptyList()),
                        ),
                ),
            delivery = DeliveryConfig(maxReconnectAttempts = maxReconnectAttempts),
        )

    private fun makeClient(
        maxReconnectAttempts: Int,
        appCtx: ApplicationContext,
    ): EngineSocketClient {
        val factory = GatewaySocketFactory()
        val buffer = GatewayBuffer(tempDir.resolve("buffer.jsonl").toString())
        val handler = NoOpOutboundMessageHandler()
        val config = makeConfig(maxReconnectAttempts)
        return factory.engineSocketClient(buffer, handler, config, appCtx)
    }

    @Test
    fun `onReconnectExhausted callback is non-null when maxReconnectAttempts is positive`() {
        val appCtx = mockk<ApplicationContext>(relaxed = true)
        val client = makeClient(maxReconnectAttempts = 3, appCtx = appCtx)

        val field = EngineSocketClient::class.java.getDeclaredField("onReconnectExhausted")
        field.isAccessible = true
        val callback = field.get(client)

        assertNotNull(callback, "onReconnectExhausted should be non-null when maxReconnectAttempts > 0")
    }

    @Test
    fun `onReconnectExhausted callback calls applicationContext close`() {
        val appCtx = mockk<ApplicationContext>(relaxed = true)
        val client = makeClient(maxReconnectAttempts = 3, appCtx = appCtx)

        val field = EngineSocketClient::class.java.getDeclaredField("onReconnectExhausted")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val callback = field.get(client) as (() -> Unit)?

        assertNotNull(callback)
        callback!!.invoke()

        verify(exactly = 1) { appCtx.close() }
    }

    @Test
    fun `onReconnectExhausted callback is non-null when maxReconnectAttempts is zero`() {
        val appCtx = mockk<ApplicationContext>(relaxed = true)
        val client = makeClient(maxReconnectAttempts = 0, appCtx = appCtx)

        val field = EngineSocketClient::class.java.getDeclaredField("onReconnectExhausted")
        field.isAccessible = true
        val callback = field.get(client)

        // With maxReconnectAttempts = 0 the callback is still wired (unlimited retries — callback never fires)
        assertNotNull(callback, "onReconnectExhausted should be wired regardless of maxReconnectAttempts value")
    }
}
