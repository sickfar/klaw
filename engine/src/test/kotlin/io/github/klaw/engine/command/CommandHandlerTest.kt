package io.github.klaw.engine.command

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.session.Session
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandHandlerTest {
    private fun makeCmd(
        command: String,
        args: String? = null,
    ) = CommandSocketMessage(
        channel = "telegram",
        chatId = "chat-1",
        command = command,
        args = args,
    )

    private fun makeSession() = mockk<Session>(relaxed = true)

    @Test
    fun `delegates to registered command`() =
        runTest {
            val mockCommand =
                mockk<EngineSlashCommand>(relaxed = true) {
                    coEvery { handle(any(), any()) } returns "Command executed"
                }
            val registry =
                mockk<EngineCommandRegistry> {
                    every { find("test") } returns mockCommand
                }
            val handler = CommandHandler(registry)
            val session = makeSession()

            val result = handler.handle(makeCmd("test"), session)

            coVerify { mockCommand.handle(makeCmd("test"), session) }
            assertEquals("Command executed", result)
        }

    @Test
    fun `returns unknown command message for unregistered command`() =
        runTest {
            val registry =
                mockk<EngineCommandRegistry> {
                    every { find("unknown") } returns null
                }
            val handler = CommandHandler(registry)

            val result = handler.handle(makeCmd("unknown"), makeSession())

            assertTrue(result.contains("Unknown command"))
            assertTrue(result.contains("/unknown"))
        }
}
