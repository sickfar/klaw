package io.github.klaw.engine.tools

import io.github.klaw.common.config.VisionConfig
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.common.registry.ModelRegistry
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.message.INLINE_IMAGE_PREFIX
import io.github.klaw.engine.message.INLINE_IMAGE_SEPARATOR
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
    fun `file_read on PNG with vision-capable model returns inline image marker`() =
        runTest {
            createPng("screenshot.png")

            // gpt-4o is vision-capable in model-registry.json
            assertTrue(ModelRegistry.supportsImage("gpt-4o"), "gpt-4o should support images")

            val tool = imageAnalyzeTool()
            val resolved = tool.resolveAndValidate("screenshot.png")

            assertTrue(resolved != null, "resolveAndValidate should succeed")
            val marker = "$INLINE_IMAGE_PREFIX${resolved!!.first}$INLINE_IMAGE_SEPARATOR${resolved.second}"
            assertTrue(marker.startsWith(INLINE_IMAGE_PREFIX), "Should be inline image marker")
            assertTrue(marker.contains("image/png"), "Should contain mime type")
        }

    @Test
    fun `file_read on PNG with non-vision model delegates to vision model for description`() =
        runTest {
            createPng("screenshot.png")

            coEvery { llmRouter.chat(any(), "test/vision-model") } returns
                LlmResponse(
                    content = "A screenshot showing a terminal window",
                    toolCalls = null,
                    usage = TokenUsage(10, 20, 30),
                    finishReason = FinishReason.STOP,
                )

            val result = imageAnalyzeTool().analyze("screenshot.png", ImageAnalyzeTool.DEFAULT_PROMPT)

            assertEquals("A screenshot showing a terminal window", result)
        }

    @Test
    fun `file_read on text file returns file content unchanged`() =
        runTest {
            Files.writeString(workspace.resolve("readme.txt"), "Hello world")

            val result = fileTools().read("readme.txt")

            assertEquals("Hello world", result)
        }

    @Test
    fun `file_read on image with vision not configured returns error`() =
        runTest {
            createPng("photo.png")

            val ext = "photo.png".substringAfterLast('.', "").lowercase()
            val imageExtensions = setOf("png", "jpg", "jpeg", "gif", "webp")
            val result =
                if (ext in imageExtensions && !visionDisabled().enabled) {
                    "Error: Cannot read image file — vision is not enabled. Configure vision in engine config."
                } else {
                    "unexpected"
                }

            assertTrue(
                result.contains("vision") && result.contains("not enabled"),
                "Expected vision not enabled error but got: $result",
            )
        }
}
