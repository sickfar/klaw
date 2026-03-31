package io.github.klaw.gateway.api

import io.github.klaw.common.command.SlashCommand
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.paths.KlawPathsSnapshot
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.command.GatewayCommandRegistry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiControllerCommandsTest {
    private fun createController(
        registry: GatewayCommandRegistry = mockGatewayRegistry(),
        channels: List<Channel> = emptyList(),
    ): ApiController {
        val config = GatewayConfig(channels = ChannelsConfig())
        val paths = mockk<KlawPathsSnapshot>(relaxed = true)
        val engineProxy = mockk<EngineApiProxy>(relaxed = true)
        return ApiController(engineProxy, config, paths, channels, registry)
    }

    private fun mockGatewayRegistry(): GatewayCommandRegistry {
        val registry = mockk<GatewayCommandRegistry>(relaxed = true)
        coEvery { registry.allCommands() } returns
            listOf(
                mockSlashCommand("new", "Start new conversation"),
                mockSlashCommand("model", "Switch model"),
                mockSlashCommand("start", "Pair gateway"),
            )
        return registry
    }

    private fun mockSlashCommand(
        name: String,
        description: String,
    ): SlashCommand =
        object : SlashCommand {
            override val name = name
            override val description = description
        }

    @Test
    fun `getCommands returns combined command list as JSON array`() =
        runTest {
            val controller = createController()

            val response = controller.getCommands()

            assertEquals(200, response.status.code)
            val body = response.body.get()
            assertTrue(body.startsWith("["))
            assertTrue(body.endsWith("]"))
            assertTrue(body.contains("\"new\""))
            assertTrue(body.contains("\"model\""))
            assertTrue(body.contains("\"start\""))
            assertTrue(body.contains("\"Start new conversation\""))
            assertTrue(body.contains("\"Switch model\""))
            assertTrue(body.contains("\"Pair gateway\""))
        }

    @Test
    fun `getCommands escapes special JSON characters`() =
        runTest {
            val registry = mockk<GatewayCommandRegistry>(relaxed = true)
            coEvery { registry.allCommands() } returns
                listOf(
                    object : SlashCommand {
                        override val name = "test"
                        override val description = "Test with \"quotes\" and \\ backslash"
                    },
                )
            val controller = createController(registry = registry)

            val response = controller.getCommands()

            assertEquals(200, response.status.code)
            val body = response.body.get()
            assertTrue(body.contains("\\\"quotes\\\""))
            assertTrue(body.contains("\\\\ backslash"))
        }

    @Test
    fun `getCommands handles empty list`() =
        runTest {
            val registry = mockk<GatewayCommandRegistry>(relaxed = true)
            coEvery { registry.allCommands() } returns emptyList()
            val controller = createController(registry = registry)

            val response = controller.getCommands()

            assertEquals(200, response.status.code)
            assertEquals("[]", response.body.get())
        }

    @Test
    fun `getCommands escapes newlines`() =
        runTest {
            val registry = mockk<GatewayCommandRegistry>(relaxed = true)
            coEvery { registry.allCommands() } returns
                listOf(
                    object : SlashCommand {
                        override val name = "multiline"
                        override val description = "Line 1\nLine 2"
                    },
                )
            val controller = createController(registry = registry)

            val response = controller.getCommands()

            assertEquals(200, response.status.code)
            val body = response.body.get()
            assertTrue(body.contains("Line 1\\nLine 2"))
        }
}
