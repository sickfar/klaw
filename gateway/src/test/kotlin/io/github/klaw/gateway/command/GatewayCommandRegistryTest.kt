package io.github.klaw.gateway.command

import io.github.klaw.gateway.api.EngineApiProxy
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.channel.IncomingMessage
import io.github.klaw.gateway.channel.OutgoingMessage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class GatewayCommandRegistryTest {
    @Test
    fun `refresh fetches commands from engine`() =
        runTest {
            val engineApi = mockk<EngineApiProxy>()
            val gatewayCommands = emptyList<GatewaySlashCommand>()

            coEvery { engineApi.send("commands_list") } returns
                """{"commands":[{"name":"model","description":"Show model info"},{"name":"status","description":"Check status"}]}"""

            val registry = GatewayCommandRegistry(engineApi, gatewayCommands)
            val commands = registry.refresh()

            assertEquals(2, commands.size)
            assertEquals("model", commands[0].name)
            assertEquals("Show model info", commands[0].description)
        }

    @Test
    fun `allCommands merges engine and gateway commands`() =
        runTest {
            val engineApi = mockk<EngineApiProxy>()
            val gatewayCmd =
                object : GatewaySlashCommand {
                    override val name = "start"
                    override val description = "Start command"

                    override suspend fun handle(
                        msg: IncomingMessage,
                        channel: Channel,
                    ) {
                        channel.send(msg.chatId, OutgoingMessage("started"))
                    }
                }

            coEvery { engineApi.send("commands_list") } returns
                """{"commands":[{"name":"model","description":"Show model info"}]}"""

            val registry = GatewayCommandRegistry(engineApi, listOf(gatewayCmd))
            val commands = registry.allCommands()

            assertEquals(2, commands.size)
            assertNotNull(commands.find { it.name == "model" })
            assertNotNull(commands.find { it.name == "start" })
        }

    @Test
    fun `findCommand returns correct command`() =
        runTest {
            val engineApi = mockk<EngineApiProxy>()

            coEvery { engineApi.send("commands_list") } returns
                """{"commands":[{"name":"model","description":"Show model info"}]}"""

            val registry = GatewayCommandRegistry(engineApi, emptyList())
            val command = registry.findCommand("model")

            assertNotNull(command)
            assertEquals("model", command?.name)
            assertNull(registry.findCommand("nonexistent"))
        }

    @Test
    fun `findGatewayCommand returns gateway command only`() =
        runTest {
            val engineApi = mockk<EngineApiProxy>()
            val gatewayCmd =
                object : GatewaySlashCommand {
                    override val name = "start"
                    override val description = "Start command"

                    override suspend fun handle(
                        msg: IncomingMessage,
                        channel: Channel,
                    ) {
                    }
                }

            val registry = GatewayCommandRegistry(engineApi, listOf(gatewayCmd))
            val found = registry.findGatewayCommand("start")

            assertNotNull(found)
            assertEquals("start", found?.name)
            assertNull(registry.findGatewayCommand("model"))
        }

    @Test
    fun `handles engine error gracefully`() =
        runTest {
            val engineApi = mockk<EngineApiProxy>()

            coEvery { engineApi.send("commands_list") } returns """{"error":"engine unavailable"}"""

            val registry = GatewayCommandRegistry(engineApi, emptyList())
            val commands = registry.refresh()

            assertEquals(0, commands.size)
        }

    @Test
    fun `refresh returns gateway commands when engine throws IOException`() =
        runTest {
            val engineApi =
                mockk<EngineApiProxy> {
                    coEvery { send(any()) } throws java.io.IOException("connection refused")
                }
            val gatewayCmd =
                mockk<GatewaySlashCommand> {
                    every { name } returns "start"
                    every { description } returns "Start"
                }
            val registry = GatewayCommandRegistry(engineApi, listOf(gatewayCmd))
            val result = registry.refresh()
            assertEquals(1, result.size)
            assertEquals("start", result[0].name)
        }

    @Test
    fun `caches commands and refreshes periodically`() =
        runTest {
            val engineApi = mockk<EngineApiProxy>()

            // First call returns one command
            coEvery { engineApi.send("commands_list") } returns
                """{"commands":[{"name":"model","description":"Show model info"}]}"""

            val registry = GatewayCommandRegistry(engineApi, emptyList())

            // First call should hit the API
            registry.allCommands()
            coVerify(exactly = 1) { engineApi.send("commands_list") }

            // Second call should use cache (no additional API call)
            registry.allCommands()
            coVerify(exactly = 1) { engineApi.send("commands_list") }
        }
}
