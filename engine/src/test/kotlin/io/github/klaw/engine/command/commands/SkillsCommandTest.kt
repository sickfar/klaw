package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.context.SkillDetail
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.context.SkillValidationEntry
import io.github.klaw.engine.context.SkillValidationReport
import io.github.klaw.engine.session.Session
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class SkillsCommandTest {
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
    fun `validate subcommand calls registry`() =
        runTest {
            val registry =
                mockk<SkillRegistry> {
                    coEvery { validate() } returns
                        SkillValidationReport(
                            listOf(
                                SkillValidationEntry("demo", "demo", "skills", valid = true, error = null),
                            ),
                        )
                }
            val result = SkillsCommand(registry).handle(commandMsg("skills", args = "validate"), session())
            assertTrue(result.contains("1 skills checked"))
        }

    @Test
    fun `list subcommand discovers and lists skills`() =
        runTest {
            val registry =
                mockk<SkillRegistry> {
                    coEvery { discover() } just Runs
                    coEvery { listDetailed() } returns listOf(SkillDetail("foo", "bar", "local"))
                }
            val result = SkillsCommand(registry).handle(commandMsg("skills", args = "list"), session())
            assertTrue(result.contains("foo"))
        }

    @Test
    fun `no args returns usage hint`() =
        runTest {
            val result = SkillsCommand(mockk()).handle(commandMsg("skills", args = null), session())
            assertEquals("Usage: /skills validate | list", result)
        }
}
