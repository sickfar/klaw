package io.github.klaw.engine.command.commands

import io.github.klaw.common.command.SlashCommand
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.command.EngineCommandRegistry
import io.github.klaw.engine.session.Session
import io.mockk.every
import io.mockk.mockk
import jakarta.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class HelpCommandTest {
    private fun commandMsg(
        command: String,
        args: String? = null,
        chatId: String = "c1",
        channel: String = "telegram",
    ) = CommandSocketMessage(channel = channel, chatId = chatId, command = command, args = args)

    private fun session(
        chatId: String = "c1",
        model: String = "test/model",
    ) = Session(
        chatId = chatId,
        model = model,
        segmentStart = Clock.System.now().toString(),
        createdAt = Clock.System.now(),
    )

    private fun mockSlashCommand(
        name: String,
        description: String,
    ): SlashCommand =
        object : SlashCommand {
            override val name = name
            override val description = description
        }

    @Test
    fun `lists all commands from registry`() =
        runTest {
            val registry =
                mockk<EngineCommandRegistry> {
                    every { allCommands() } returns
                        listOf(
                            mockSlashCommand("new", "Start new conversation"),
                            mockSlashCommand("model", "Switch model"),
                        )
                }
            val cmd = HelpCommand(Provider { registry })
            val result = cmd.handle(commandMsg("help"), session())
            assertTrue(result.contains("/new"))
            assertTrue(result.contains("Start new conversation"))
            assertTrue(result.contains("/model"))
        }

    @Test
    fun `returns empty string when no commands registered`() =
        runTest {
            val registry =
                mockk<EngineCommandRegistry> {
                    every { allCommands() } returns emptyList()
                }
            val cmd = HelpCommand(Provider { registry })
            val result = cmd.handle(commandMsg("help"), session())
            assertTrue(result.isEmpty())
        }
}
