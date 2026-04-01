package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.memory.MemoryCategoryInfo
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.session.Session
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class MemoryCommandTest {
    private lateinit var memoryService: MemoryService
    private lateinit var cmd: MemoryCommand

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

    @BeforeEach
    fun setup() {
        memoryService = mockk()
        cmd = MemoryCommand(memoryService)
    }

    @Test
    fun `returns no memories message when empty`() =
        runTest {
            coEvery { memoryService.getTopCategories(10) } returns emptyList()
            assertEquals("No memories stored yet.", cmd.handle(commandMsg("memory"), session()))
        }

    @Test
    fun `returns memory map with categories`() =
        runTest {
            coEvery { memoryService.getTopCategories(10) } returns
                listOf(
                    MemoryCategoryInfo(id = 1, name = "Preferences", accessCount = 5, entryCount = 5),
                    MemoryCategoryInfo(id = 2, name = "Projects", accessCount = 3, entryCount = 3),
                )
            coEvery { memoryService.getTotalCategoryCount() } returns 2L

            val result = cmd.handle(commandMsg("memory"), session())
            assertTrue(result.contains("Preferences (5 entries)"))
            assertTrue(result.contains("Projects (3 entries)"))
        }

    @Test
    fun `shows remaining count when more than limit`() =
        runTest {
            coEvery { memoryService.getTopCategories(10) } returns
                listOf(
                    MemoryCategoryInfo(id = 1, name = "Cat1", accessCount = 1, entryCount = 1),
                )
            coEvery { memoryService.getTotalCategoryCount() } returns 15L

            val result = cmd.handle(commandMsg("memory"), session())
            assertTrue(result.contains("...and 14 more categories"))
        }
}
