package io.github.klaw.engine.workspace

import io.github.klaw.engine.context.KlawWorkspaceLoader
import io.github.klaw.engine.memory.MemoryService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class WorkspaceLoaderTest {
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
    fun `buildSystemPrompt includes SOUL_md content`() =
        runTest {
            Files.writeString(workspace.resolve("SOUL.md"), "Be kind and helpful.")
            loader.initialize()
            val prompt = loader.loadSystemPrompt()
            assertTrue(prompt.contains("Be kind and helpful."), "SOUL content missing from: $prompt")
            assertTrue(prompt.contains("## Soul"), "## Soul header missing from: $prompt")
        }

    @Test
    fun `buildSystemPrompt includes IDENTITY_md content`() =
        runTest {
            Files.writeString(workspace.resolve("IDENTITY.md"), "My name is Klaw.")
            loader.initialize()
            val prompt = loader.loadSystemPrompt()
            assertTrue(prompt.contains("My name is Klaw."), "IDENTITY missing from: $prompt")
            assertTrue(prompt.contains("## Identity"), "## Identity header missing from: $prompt")
        }

    @Test
    fun `buildSystemPrompt includes USER_md as About the User`() =
        runTest {
            Files.writeString(workspace.resolve("USER.md"), "User likes cats.")
            loader.initialize()
            val prompt = loader.loadSystemPrompt()
            assertTrue(prompt.contains("User likes cats."), "USER content missing from: $prompt")
            assertTrue(prompt.contains("## About the User"), "## About the User header missing from: $prompt")
        }

    @Test
    fun `buildSystemPrompt includes AGENTS_md content`() =
        runTest {
            Files.writeString(workspace.resolve("AGENTS.md"), "Always be helpful.")
            loader.initialize()
            val prompt = loader.loadSystemPrompt()
            assertTrue(prompt.contains("Always be helpful."), "AGENTS missing from: $prompt")
            assertTrue(prompt.contains("## Instructions"), "## Instructions header missing from: $prompt")
        }

    @Test
    fun `buildSystemPrompt handles missing optional files`() =
        runTest {
            loader.initialize()
            val prompt = loader.loadSystemPrompt()
            assertFalse(prompt.contains("null"), "null in prompt: $prompt")
        }

    @Test
    fun `missing workspace dir handled gracefully`() =
        runTest {
            val missing = workspace.resolve("nonexistent")
            val safeLoader = KlawWorkspaceLoader(missing, memoryService)
            safeLoader.initialize()
            assertEquals("", safeLoader.loadSystemPrompt())
        }
}
