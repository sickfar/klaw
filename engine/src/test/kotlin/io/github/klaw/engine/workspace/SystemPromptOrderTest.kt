package io.github.klaw.engine.workspace

import io.github.klaw.engine.context.KlawWorkspaceLoader
import io.github.klaw.engine.memory.MemoryService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class SystemPromptOrderTest {
    @TempDir
    lateinit var workspace: Path

    private val memoryService = mockk<MemoryService>(relaxed = true)
    private lateinit var loader: KlawWorkspaceLoader

    @BeforeEach
    fun setup() {
        coEvery { memoryService.save(any(), any()) } returns "OK"

        Files.writeString(workspace.resolve("SOUL.md"), "SOUL_CONTENT")
        Files.writeString(workspace.resolve("IDENTITY.md"), "IDENTITY_CONTENT")
        Files.writeString(workspace.resolve("USER.md"), "USER_CONTENT")
        Files.writeString(workspace.resolve("AGENTS.md"), "AGENTS_CONTENT")
        Files.writeString(workspace.resolve("TOOLS.md"), "TOOLS_CONTENT")

        loader = KlawWorkspaceLoader(workspace, memoryService)
    }

    @Test
    fun `SOUL section appears before IDENTITY`() =
        runTest {
            loader.initialize()
            val prompt = loader.loadSystemPrompt()
            assertTrue(
                prompt.indexOf("SOUL_CONTENT") < prompt.indexOf("IDENTITY_CONTENT"),
                "SOUL must precede IDENTITY in: $prompt",
            )
        }

    @Test
    fun `IDENTITY appears before USER`() =
        runTest {
            loader.initialize()
            val prompt = loader.loadSystemPrompt()
            assertTrue(
                prompt.indexOf("IDENTITY_CONTENT") < prompt.indexOf("USER_CONTENT"),
                "IDENTITY must precede USER in: $prompt",
            )
        }

    @Test
    fun `USER appears before AGENTS`() =
        runTest {
            loader.initialize()
            val prompt = loader.loadSystemPrompt()
            assertTrue(
                prompt.indexOf("USER_CONTENT") < prompt.indexOf("AGENTS_CONTENT"),
                "USER must precede AGENTS in: $prompt",
            )
        }

    @Test
    fun `AGENTS appears before TOOLS`() =
        runTest {
            loader.initialize()
            val prompt = loader.loadSystemPrompt()
            assertTrue(
                prompt.indexOf("AGENTS_CONTENT") < prompt.indexOf("TOOLS_CONTENT"),
                "AGENTS must precede TOOLS in: $prompt",
            )
        }

    @Test
    fun `USER_md appears in system prompt as About the User`() =
        runTest {
            loader.initialize()
            val prompt = loader.loadSystemPrompt()
            assertTrue(prompt.contains("## About the User"), "## About the User should be in system prompt")
            assertTrue(prompt.contains("USER_CONTENT"), "USER_CONTENT should be in system prompt")
        }
}
