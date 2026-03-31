package io.github.klaw.gateway.api

import io.github.klaw.common.command.SlashCommand
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.paths.KlawPathsSnapshot
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.command.GatewayCommandRegistry
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
    fun `getCommands returns wrapped JSON with commands array`() =
        runTest {
            val controller = createController()

            val response = controller.getCommands()

            assertEquals(200, response.status.code)
            val body = response.body.get()
            val json = Json.parseToJsonElement(body).jsonObject
            val cmds = json["commands"]?.jsonArray
            assertEquals(3, cmds?.size)

            val names = cmds?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
            assertTrue(names?.contains("new") == true)
            assertTrue(names?.contains("model") == true)
            assertTrue(names?.contains("start") == true)

            val descs = cmds?.map { it.jsonObject["description"]?.jsonPrimitive?.content }
            assertTrue(descs?.contains("Start new conversation") == true)
            assertTrue(descs?.contains("Switch model") == true)
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
            val json = Json.parseToJsonElement(body).jsonObject
            val cmd = json["commands"]?.jsonArray?.first()?.jsonObject
            assertEquals("Test with \"quotes\" and \\ backslash", cmd?.get("description")?.jsonPrimitive?.content)
        }

    @Test
    fun `getCommands handles empty list`() =
        runTest {
            val registry = mockk<GatewayCommandRegistry>(relaxed = true)
            coEvery { registry.allCommands() } returns emptyList()
            val controller = createController(registry = registry)

            val response = controller.getCommands()

            assertEquals(200, response.status.code)
            val body = response.body.get()
            val json = Json.parseToJsonElement(body).jsonObject
            val cmds = json["commands"]?.jsonArray
            assertEquals(0, cmds?.size)
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
            val json = Json.parseToJsonElement(body).jsonObject
            val cmd = json["commands"]?.jsonArray?.first()?.jsonObject
            assertEquals("Line 1\nLine 2", cmd?.get("description")?.jsonPrimitive?.content)
        }
}
