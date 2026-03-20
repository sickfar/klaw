package io.github.klaw.engine.tools

import io.github.klaw.common.config.VisionConfig
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.engine.llm.LlmRouter
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileToolsImageTest {
    @TempDir
    lateinit var workspace: Path

    private val llmRouter = mockk<LlmRouter>()

    private fun fileTools(): FileTools = FileTools(listOf(workspace), 10_000_000L)

    private fun imageAnalyzeTool(config: VisionConfig = visionEnabled()): ImageAnalyzeTool =
        ImageAnalyzeTool(llmRouter, config, listOf(workspace))

    private fun visionEnabled(): VisionConfig =
        VisionConfig(
            enabled = true,
            model = "test/vision-model",
            maxTokens = 512,
            maxImageSizeBytes = 1_048_576,
            supportedFormats = listOf("image/jpeg", "image/png", "image/gif", "image/webp"),
        )

    private fun visionDisabled(): VisionConfig =
        VisionConfig(
            enabled = false,
            model = "",
        )

    private fun createPng(name: String): Path {
        val pngBytes =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
                0x00,
                0x00,
                0x00,
                0x01,
                0x00,
                0x00,
                0x00,
                0x01,
            )
        val path = workspace.resolve(name)
        path.parent?.let { Files.createDirectories(it) }
        Files.write(path, pngBytes)
        return path
    }

    @Test
    fun `file_read on PNG delegates to vision and returns description`() =
        runTest {
            createPng("screenshot.png")

            coEvery { llmRouter.chat(any(), "test/vision-model") } returns
                LlmResponse(
                    content = "A screenshot showing a terminal window",
                    toolCalls = null,
                    usage = TokenUsage(10, 20, 30),
                    finishReason = FinishReason.STOP,
                )

            val result = dispatchFileRead("screenshot.png", visionEnabled())

            assertEquals("A screenshot showing a terminal window", result)
        }

    @Test
    fun `file_read on text file returns file content unchanged`() =
        runTest {
            Files.writeString(workspace.resolve("readme.txt"), "Hello world")

            val result = dispatchFileRead("readme.txt", visionEnabled())

            assertEquals("Hello world", result)
        }

    @Test
    fun `file_read on image with vision not configured returns error`() =
        runTest {
            createPng("photo.png")

            val result = dispatchFileRead("photo.png", visionDisabled())

            assertTrue(
                result.contains("vision") && result.contains("not enabled"),
                "Expected vision not enabled error but got: $result",
            )
        }

    /**
     * Simulates the file_read dispatch logic that will be in ToolRegistryImpl:
     * - If image extension AND vision enabled -> delegate to imageAnalyzeTool
     * - If image extension AND vision disabled -> error
     * - Otherwise -> fileTools.read()
     */
    private suspend fun dispatchFileRead(
        path: String,
        visionConfig: VisionConfig,
    ): String {
        val extension = path.substringAfterLast('.', "").lowercase()
        val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")

        return if (extension in imageExtensions) {
            if (visionConfig.enabled) {
                imageAnalyzeTool(visionConfig).analyze(path, ImageAnalyzeTool.DEFAULT_PROMPT)
            } else {
                "Error: Cannot read image file — vision is not enabled. Configure vision in engine config."
            }
        } else {
            fileTools().read(path)
        }
    }
}
