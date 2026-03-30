package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.session.Session
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.time.Clock

class MemoryCommandTest {
    private fun commandMsg(
        command: String,
        args: String? = null,
        chatId: String = "c1",
    ) = CommandSocketMessage(channel = "telegram", chatId = chatId, command = command, args = args)

    private fun session() =
        Session(
            chatId = "c1",
            model = "test/model",
            segmentStart = Clock.System.now().toString(),
            createdAt = Clock.System.now(),
        )

    @Test
    fun `returns MEMORY_md content`() =
        runTest {
            val dir = createTempDirectory()
            dir.resolve("MEMORY.md").writeText("# Memory")
            val cmd = MemoryCommand(workspacePath = dir)
            assertEquals("# Memory", cmd.handle(commandMsg("memory"), session()))
        }

    @Test
    fun `returns not-found when no MEMORY_md`() =
        runTest {
            val cmd = MemoryCommand(workspacePath = Path.of("/nonexistent"))
            assertEquals("No MEMORY.md found in workspace.", cmd.handle(commandMsg("memory"), session()))
        }
}
