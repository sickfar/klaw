package io.github.klaw.common.command

import io.github.klaw.common.config.CommandConfig
import kotlin.test.Test
import kotlin.test.assertEquals

class SlashCommandTest {
    @Test
    fun `CommandConfig implements SlashCommand`() {
        val cmd = CommandConfig("help", "Show help")
        val slash: SlashCommand = cmd
        assertEquals("help", slash.name)
        assertEquals("Show help", slash.description)
    }
}
