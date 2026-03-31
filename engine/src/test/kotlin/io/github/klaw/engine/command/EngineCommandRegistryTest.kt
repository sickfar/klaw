package io.github.klaw.engine.command

import io.github.klaw.common.command.SlashCommand
import io.github.klaw.common.config.CommandConfig
import io.github.klaw.common.config.EngineConfig
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EngineCommandRegistryTest {
    private fun mockCommand(
        name: String,
        description: String = "desc",
    ) = mockk<EngineSlashCommand> {
        every { this@mockk.name } returns name
        every { this@mockk.description } returns description
    }

    private fun makeConfig(commands: List<CommandConfig> = emptyList()) =
        mockk<EngineConfig> {
            every { this@mockk.commands } returns commands
        }

    @Test
    fun `find returns command by name`() {
        val newCmd = mockCommand("new", "Start new conversation")
        val modelCmd = mockCommand("model", "Switch model")
        val registry =
            EngineCommandRegistry(
                commands = listOf(newCmd, modelCmd),
                config = makeConfig(),
            )

        assertEquals(newCmd, registry.find("new"))
        assertEquals(modelCmd, registry.find("model"))
    }

    @Test
    fun `find returns null for unknown command`() {
        val registry =
            EngineCommandRegistry(
                commands = listOf(mockCommand("new")),
                config = makeConfig(),
            )

        assertNull(registry.find("unknown"))
    }

    @Test
    fun `allCommands includes engine commands and config commands`() {
        val newCmd = mockCommand("new", "Start new")
        val customCmd = CommandConfig(name = "custom", description = "Custom command")
        val registry =
            EngineCommandRegistry(
                commands = listOf(newCmd),
                config = makeConfig(commands = listOf(customCmd)),
            )

        val all = registry.allCommands()

        assertEquals(2, all.size)
        assertTrue(all.any { it.name == "new" })
        assertTrue(all.any { it.name == "custom" })
    }

    @Test
    fun `allCommands returns only engine commands when config is empty`() {
        val newCmd = mockCommand("new")
        val registry =
            EngineCommandRegistry(
                commands = listOf(newCmd),
                config = makeConfig(),
            )

        val all = registry.allCommands()

        assertEquals(1, all.size)
        assertEquals("new", all[0].name)
    }
}
