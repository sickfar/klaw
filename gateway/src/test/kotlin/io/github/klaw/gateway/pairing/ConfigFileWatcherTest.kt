package io.github.klaw.gateway.pairing

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.config.encodeGatewayConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ConfigFileWatcherTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `detects gateway json change and invokes callback with parsed config`() {
        val configFile = File(tempDir, "gateway.json")
        val initialConfig = GatewayConfig(channels = ChannelsConfig())
        configFile.writeText(encodeGatewayConfig(initialConfig))

        val receivedConfig = AtomicReference<GatewayConfig>()
        val latch = CountDownLatch(1)

        val watcher = ConfigFileWatcher(tempDir.absolutePath)
        watcher.startWatching { config ->
            receivedConfig.set(config)
            latch.countDown()
        }

        try {
            Thread.sleep(500)
            val updatedConfig =
                GatewayConfig(
                    channels =
                        ChannelsConfig(
                            telegram =
                                TelegramConfig(
                                    token = "new-token",
                                    allowedChats = listOf(AllowedChat("telegram_123", listOf("user1"))),
                                ),
                        ),
                )
            configFile.writeText(encodeGatewayConfig(updatedConfig))

            val received = latch.await(10, TimeUnit.SECONDS)
            assert(received) { "Callback was not invoked within timeout" }
            val parsed = receivedConfig.get()
            assertNotNull(parsed)
            assertEquals("new-token", parsed.channels.telegram?.token)
            assertEquals(
                1,
                parsed.channels.telegram
                    ?.allowedChats
                    ?.size,
            )
        } finally {
            watcher.stopWatching()
        }
    }

    @Test
    fun `ignores changes to non-gateway files`() {
        val configFile = File(tempDir, "gateway.json")
        configFile.writeText(encodeGatewayConfig(GatewayConfig(channels = ChannelsConfig())))

        val callCount =
            java.util.concurrent.atomic
                .AtomicInteger(0)

        val watcher = ConfigFileWatcher(tempDir.absolutePath)
        watcher.startWatching { callCount.incrementAndGet() }

        try {
            Thread.sleep(500)
            File(tempDir, "engine.json").writeText("{}")
            Thread.sleep(2000)
            assertEquals(0, callCount.get())
        } finally {
            watcher.stopWatching()
        }
    }

    @Test
    fun `start and stop lifecycle`() {
        val configFile = File(tempDir, "gateway.json")
        configFile.writeText(encodeGatewayConfig(GatewayConfig(channels = ChannelsConfig())))

        val watcher = ConfigFileWatcher(tempDir.absolutePath)
        watcher.startWatching { }
        Thread.sleep(200)
        watcher.stopWatching()
        // Should not throw or hang
    }
}
