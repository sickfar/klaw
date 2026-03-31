package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.workspace.HeartbeatRunner
import io.github.klaw.engine.workspace.HeartbeatRunnerFactory
import io.mockk.every
import io.mockk.mockk
import jakarta.inject.Provider
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory
import kotlin.time.Clock

class UseForHeartbeatCommandTest {
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

    @Test
    fun `returns disabled message when runner is null`() =
        runTest {
            val factory = mockk<HeartbeatRunnerFactory> { every { runner } returns null }
            val provider = Provider { factory }
            val cmd = UseForHeartbeatCommand(provider)
            val result = cmd.handle(commandMsg("heartbeat", channel = "telegram"), session())
            assertTrue(result.contains("disabled"))
        }

    @Test
    fun `updates runner delivery and returns confirmation`() =
        runTest {
            val heartbeatRunner = mockk<HeartbeatRunner>(relaxed = true)
            val factory = mockk<HeartbeatRunnerFactory> { every { runner } returns heartbeatRunner }
            val provider = Provider { factory }
            val cmd = UseForHeartbeatCommand(provider)
            cmd.configPath = createTempDirectory()
            val result = cmd.handle(commandMsg("heartbeat", channel = "telegram", chatId = "c1"), session())
            assertTrue(result.contains("telegram"))
            assertTrue(result.contains("c1"))
        }

    @Test
    fun `returns error message on exception`() =
        runTest {
            val factory = mockk<HeartbeatRunnerFactory> { every { runner } throws RuntimeException("fail") }
            val provider = Provider { factory }
            val cmd = UseForHeartbeatCommand(provider)
            val result = cmd.handle(commandMsg("heartbeat", channel = "telegram"), session())
            assertTrue(result.contains("Failed"))
        }
}
