package io.github.klaw.engine.mcp

import io.github.klaw.common.config.McpConfig
import io.github.klaw.common.config.McpServerConfig
import io.micronaut.context.event.ShutdownEvent
import io.micronaut.context.event.StartupEvent
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class McpLifecycleTest {
    @Test
    fun startupWithNoServersDoesNothing() {
        val config = McpConfig()
        val registry = McpToolRegistry()
        val listener = McpStartupListener(config, registry)
        listener.onApplicationEvent(mockk<StartupEvent>())
        assertTrue(registry.listTools().isEmpty())
    }

    @Test
    fun startupSkipsDisabledServers() {
        val config =
            McpConfig(
                servers =
                    mapOf(
                        "disabled" to
                            McpServerConfig(
                                enabled = false,
                                transport = "http",
                                url = "http://localhost:8080",
                            ),
                    ),
            )
        val registry = McpToolRegistry()
        val listener = McpStartupListener(config, registry)
        listener.onApplicationEvent(mockk<StartupEvent>())
        assertTrue(registry.listTools().isEmpty())
    }

    @Test
    fun startupHandlesInvalidTransportGracefully() {
        val config =
            McpConfig(
                servers =
                    mapOf(
                        "bad" to
                            McpServerConfig(
                                transport = "websocket",
                            ),
                    ),
            )
        val registry = McpToolRegistry()
        val listener = McpStartupListener(config, registry)
        listener.onApplicationEvent(mockk<StartupEvent>())
        assertTrue(registry.listTools().isEmpty())
    }

    @Test
    fun startupHandlesMissingCommandGracefully() {
        val config =
            McpConfig(
                servers =
                    mapOf(
                        "nocommand" to
                            McpServerConfig(
                                transport = "stdio",
                                command = null,
                            ),
                    ),
            )
        val registry = McpToolRegistry()
        val listener = McpStartupListener(config, registry)
        listener.onApplicationEvent(mockk<StartupEvent>())
        assertTrue(registry.listTools().isEmpty())
    }

    @Test
    fun startupHandlesMissingUrlGracefully() {
        val config =
            McpConfig(
                servers =
                    mapOf(
                        "nourl" to
                            McpServerConfig(
                                transport = "http",
                                url = null,
                            ),
                    ),
            )
        val registry = McpToolRegistry()
        val listener = McpStartupListener(config, registry)
        listener.onApplicationEvent(mockk<StartupEvent>())
        assertTrue(registry.listTools().isEmpty())
    }

    @Test
    fun shutdownClosesAllClients() =
        runBlocking {
            val registry = McpToolRegistry()
            val transport = FakeTransport()
            registry.registerClient("srv", McpClient(transport, "srv"))

            val listener = McpShutdownListener(registry)
            listener.onApplicationEvent(mockk<ShutdownEvent>())
            assertTrue(registry.listTools().isEmpty())
            assertEquals(false, transport.isOpen)
        }
}
