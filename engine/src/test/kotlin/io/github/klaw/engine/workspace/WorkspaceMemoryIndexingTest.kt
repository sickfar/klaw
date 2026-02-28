package io.github.klaw.engine.workspace

import io.github.klaw.engine.context.KlawWorkspaceLoader
import io.github.klaw.engine.memory.MemoryService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceMemoryIndexingTest {
    @TempDir
    lateinit var workspace: Path

    private val memoryService = mockk<MemoryService>(relaxed = true)
    private lateinit var loader: KlawWorkspaceLoader

    @BeforeEach
    fun setup() {
        coEvery { memoryService.save(any(), any()) } returns "OK"
        loader = KlawWorkspaceLoader(workspace, memoryService)
    }

    @Test
    fun `MEMORY_md indexed in sqlite-vec`() =
        runTest {
            Files.writeString(workspace.resolve("MEMORY.md"), "Important fact: Kotlin is great.")
            loader.initialize()
            coVerify { memoryService.save(any(), eq("MEMORY.md")) }
        }

    @Test
    fun `memory daily logs indexed`() =
        runTest {
            val memoryDir = workspace.resolve("memory")
            Files.createDirectories(memoryDir)
            Files.writeString(memoryDir.resolve("2024-01-15.md"), "Learned something new.")
            loader.initialize()
            coVerify { memoryService.save(any(), eq("2024-01-15.md")) }
        }

    @Test
    fun `reindexing calls save for each source independently`() =
        runTest {
            Files.writeString(workspace.resolve("MEMORY.md"), "Main memory content.")
            val memoryDir = workspace.resolve("memory")
            Files.createDirectories(memoryDir)
            Files.writeString(memoryDir.resolve("2024-01-01.md"), "Day log.")
            loader.initialize()
            coVerify { memoryService.save(any(), eq("MEMORY.md")) }
            coVerify { memoryService.save(any(), eq("2024-01-01.md")) }
        }

    @Test
    fun `missing MEMORY_md does not throw`() =
        runTest {
            loader.initialize()
            coVerify(exactly = 0) { memoryService.save(any(), eq("MEMORY.md")) }
        }
}
