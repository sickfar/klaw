package io.github.klaw.engine.workspace

import io.github.klaw.common.config.AgentConfig
import io.github.klaw.common.config.ChunkingConfig
import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EmbeddingConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.MemoryConfig
import io.github.klaw.common.config.ProcessingConfig
import io.github.klaw.common.config.RoutingConfig
import io.github.klaw.common.config.SearchConfig
import io.github.klaw.common.config.TaskRoutingConfig
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

    private val config =
        EngineConfig(
            providers = emptyMap(),
            models = emptyMap(),
            routing = RoutingConfig(default = "t/m", fallback = emptyList(), tasks = TaskRoutingConfig("t/m", "t/m")),
            memory =
                MemoryConfig(
                    embedding = EmbeddingConfig(type = "onnx", model = "test"),
                    chunking = ChunkingConfig(size = 512, overlap = 64),
                    search = SearchConfig(topK = 10),
                ),
            context = ContextConfig(subagentHistory = 5),
            processing = ProcessingConfig(debounceMs = 100, maxConcurrentLlm = 1, maxToolCallRounds = 5),
            agents = mapOf("default" to AgentConfig(workspace = "/tmp/klaw-test-workspace")),
        )

    @BeforeEach
    fun setup() {
        coEvery { memoryService.save(any(), any(), any()) } returns "OK"
        coEvery { memoryService.hasCategories() } returns false
        loader = KlawWorkspaceLoader(workspace, memoryService, config)
    }

    @Test
    fun `MEMORY_md indexed with categories`() =
        runTest {
            Files.writeString(
                workspace.resolve("MEMORY.md"),
                "## Facts\nImportant fact: Kotlin is great.",
            )
            loader.initialize()
            coVerify { memoryService.save(eq("Important fact: Kotlin is great."), eq("Facts"), eq("MEMORY.md")) }
        }

    @Test
    fun `memory daily logs indexed`() =
        runTest {
            val memoryDir = workspace.resolve("memory")
            Files.createDirectories(memoryDir)
            Files.writeString(memoryDir.resolve("2024-01-15.md"), "## Notes\nLearned something new.")
            loader.initialize()
            coVerify { memoryService.save(eq("Learned something new."), eq("Notes"), eq("2024-01-15.md")) }
        }

    @Test
    fun `reindexing calls save for each source independently`() =
        runTest {
            Files.writeString(workspace.resolve("MEMORY.md"), "## Main\nMain memory content.")
            val memoryDir = workspace.resolve("memory")
            Files.createDirectories(memoryDir)
            Files.writeString(memoryDir.resolve("2024-01-01.md"), "## Day\nDay log.")
            loader.initialize()
            coVerify { memoryService.save(eq("Main memory content."), eq("Main"), eq("MEMORY.md")) }
            coVerify { memoryService.save(eq("Day log."), eq("Day"), eq("2024-01-01.md")) }
        }

    @Test
    fun `missing MEMORY_md does not throw`() =
        runTest {
            loader.initialize()
            coVerify(exactly = 0) { memoryService.save(any(), any(), any()) }
        }

    @Test
    fun `skips indexation when categories already exist`() =
        runTest {
            coEvery { memoryService.hasCategories() } returns true
            Files.writeString(workspace.resolve("MEMORY.md"), "## Facts\nSome content.")
            loader.initialize()
            coVerify(exactly = 0) { memoryService.save(any(), any(), any()) }
        }
}
