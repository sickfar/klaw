package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class NewConversationCommandTest {
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
    fun `resets session and returns confirmation`() =
        runTest {
            val messageRepo = mockk<MessageRepository>(relaxed = true)
            val sessionMgr = mockk<SessionManager>(relaxed = true)
            val cmd = NewConversationCommand(messageRepo, sessionMgr)
            val result = cmd.handle(commandMsg("new", chatId = "c1"), session())
            assertEquals("New conversation started. Previous context cleared.", result)
            coVerify { messageRepo.appendSessionBreak("c1") }
            coVerify { sessionMgr.resetSegment("c1") }
        }
}
