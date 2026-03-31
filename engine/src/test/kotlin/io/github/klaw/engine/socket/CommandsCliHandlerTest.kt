package io.github.klaw.engine.socket

import io.github.klaw.common.config.CommandConfig
import io.github.klaw.engine.command.EngineCommandRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CommandsCliHandlerTest {
    private fun mockRegistry(commands: List<CommandConfig>): EngineCommandRegistry =
        mockk<EngineCommandRegistry> {
            every { allCommands() } returns commands
        }

    @Test
    fun `handleCommandsList returns JSON with commands`() {
        val commands =
            listOf(
                CommandConfig("new", "Start new conversation"),
                CommandConfig("help", "Show help"),
                CommandConfig("model", "Switch model"),
            )
        val registry = mockRegistry(commands)
        val handler = CommandsCliHandler(registry)

        val result = handler.handleCommandsList()

        val json = Json.parseToJsonElement(result).jsonObject
        val cmds = json["commands"]?.jsonArray
        assertEquals(3, cmds?.size)

        val names = cmds?.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertEquals(listOf("new", "help", "model"), names)
    }

    @Test
    fun `handleCommandsList escapes special JSON characters in name and description`() {
        val commands =
            listOf(
                CommandConfig("cmd\"quote", "Description with \"quotes\""),
                CommandConfig("cmd\\backslash", "Line1\nLine2\tTabbed"),
            )
        val registry = mockRegistry(commands)
        val handler = CommandsCliHandler(registry)

        val result = handler.handleCommandsList()

        // Should not throw - valid JSON
        val json = Json.parseToJsonElement(result).jsonObject
        val cmds = json["commands"]?.jsonArray
        assertEquals(2, cmds?.size)

        val first = cmds?.get(0)?.jsonObject
        assertEquals("cmd\"quote", first?.get("name")?.jsonPrimitive?.content)
        assertEquals("Description with \"quotes\"", first?.get("description")?.jsonPrimitive?.content)

        val second = cmds?.get(1)?.jsonObject
        assertEquals("cmd\\backslash", second?.get("name")?.jsonPrimitive?.content)
        assertEquals("Line1\nLine2\tTabbed", second?.get("description")?.jsonPrimitive?.content)
    }

    @Test
    fun `handleCommandsList returns empty commands array when no commands`() {
        val registry = mockRegistry(emptyList())
        val handler = CommandsCliHandler(registry)

        val result = handler.handleCommandsList()

        val json = Json.parseToJsonElement(result).jsonObject
        val cmds = json["commands"]?.jsonArray
        assertEquals(0, cmds?.size)
    }
}
