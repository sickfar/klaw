package io.github.klaw.engine.tools

import io.github.klaw.common.config.VisionConfig
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.TextContentPart
import io.github.klaw.common.llm.TokenUsage
import io.github.klaw.engine.llm.LlmRouter
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ImageAnalyzeToolTest {
    @TempDir
    lateinit var workspace: Path

    private val llmRouter = mockk<LlmRouter>()

    private fun tool(
        config: VisionConfig =
            VisionConfig(
                enabled = true,
                model = "test/vision-model",
                maxTokens = 512,
                maxImageSizeBytes = 1_048_576,
                supportedFormats = listOf("image/jpeg", "image/png", "image/gif", "image/webp"),
            ),
    ): ImageAnalyzeTool = ImageAnalyzeTool(llmRouter, config, listOf(workspace))

    private fun createPng(name: String = "test.png"): Path {
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
    fun `happy path - reads PNG and returns vision model description`() =
        runTest {
            createPng("image.png")

            val requestSlot = slot<LlmRequest>()
            coEvery { llmRouter.chat(capture(requestSlot), "test/vision-model") } returns
                LlmResponse(
                    content = "A small test image",
                    toolCalls = null,
                    usage = TokenUsage(10, 20, 30),
                    finishReason = FinishReason.STOP,
                )

            val result = tool().analyze("image.png", null)

            assertEquals("A small test image", result)
            val req = requestSlot.captured
            assertEquals(2, req.messages.size)
            assertEquals("system", req.messages[0].role)
            assertEquals("user", req.messages[1].role)
            assertTrue(req.messages[1].contentParts != null)
            assertEquals(2, req.messages[1].contentParts!!.size)
        }

    @Test
    fun `path traversal rejected`() =
        runTest {
            val result = tool().analyze("../etc/passwd", null)

            assertTrue(result.contains("Access denied") || result.contains("Error"), "Expected error but got: $result")
        }

    @Test
    fun `symlink outside workspace rejected`() =
        runTest {
            val outsideDir = Files.createTempDirectory("outside")
            val outsideFile = outsideDir.resolve("secret.png")
            Files.write(outsideFile, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))

            val link = workspace.resolve("link.png")
            Files.createSymbolicLink(link, outsideFile)

            val result = tool().analyze("link.png", null)

            assertTrue(result.contains("Access denied") || result.contains("Error"), "Expected error but got: $result")
            outsideFile.toFile().delete()
            outsideDir.toFile().delete()
        }

    @Test
    fun `file not found returns error`() =
        runTest {
            val result = tool().analyze("nonexistent.png", null)

            assertTrue(result.contains("not found"), "Expected 'not found' but got: $result")
        }

    @Test
    fun `file too large returns error`() =
        runTest {
            val config =
                VisionConfig(
                    enabled = true,
                    model = "test/vision-model",
                    maxImageSizeBytes = 10,
                    supportedFormats = listOf("image/png"),
                )
            val bigFile = workspace.resolve("big.png")
            Files.write(bigFile, ByteArray(100))

            val result = ImageAnalyzeTool(llmRouter, config, listOf(workspace)).analyze("big.png", null)

            assertTrue(
                result.contains("too large") || result.contains("exceeds"),
                "Expected size error but got: $result",
            )
        }

    @Test
    fun `unsupported MIME type returns error`() =
        runTest {
            Files.writeString(workspace.resolve("file.txt"), "not an image")

            val result = tool().analyze("file.txt", null)

            assertTrue(
                result.contains("unsupported") || result.contains("Unsupported"),
                "Expected unsupported type error but got: $result",
            )
        }

    @Test
    fun `custom prompt passed to vision model`() =
        runTest {
            createPng("photo.png")

            val requestSlot = slot<LlmRequest>()
            coEvery { llmRouter.chat(capture(requestSlot), "test/vision-model") } returns
                LlmResponse(
                    content = "Custom analysis",
                    toolCalls = null,
                    usage = TokenUsage(10, 20, 30),
                    finishReason = FinishReason.STOP,
                )

            tool().analyze("photo.png", "Count the people in this image")

            val userMsg = requestSlot.captured.messages[1]
            val textPart = userMsg.contentParts!!.first { it is TextContentPart } as TextContentPart
            assertEquals("Count the people in this image", textPart.text)
        }

    @Test
    fun `default prompt is used when prompt is null`() =
        runTest {
            createPng("default.png")

            val requestSlot = slot<LlmRequest>()
            coEvery { llmRouter.chat(capture(requestSlot), "test/vision-model") } returns
                LlmResponse(
                    content = "Description",
                    toolCalls = null,
                    usage = TokenUsage(10, 20, 30),
                    finishReason = FinishReason.STOP,
                )

            tool().analyze("default.png", null)

            val userMsg = requestSlot.captured.messages[1]
            val textPart = userMsg.contentParts!!.first { it is TextContentPart } as TextContentPart
            assertEquals(ImageAnalyzeTool.DEFAULT_PROMPT, textPart.text)
        }

    @Test
    fun `vision model empty returns error`() =
        runTest {
            createPng("empty-model.png")

            val config =
                VisionConfig(
                    enabled = true,
                    model = "",
                    supportedFormats = listOf("image/png"),
                )

            val result = ImageAnalyzeTool(llmRouter, config, listOf(workspace)).analyze("empty-model.png", null)

            assertTrue(
                result.contains("not configured") || result.contains("Error"),
                "Expected config error but got: $result",
            )
        }

    @Test
    fun `cancellation exception is re-thrown`() =
        runTest {
            createPng("cancel.png")

            coEvery { llmRouter.chat(any(), any()) } throws CancellationException("cancelled")

            try {
                tool().analyze("cancel.png", null)
                throw AssertionError("Expected CancellationException")
            } catch (e: CancellationException) {
                assertEquals("cancelled", e.message)
            }
        }

    @Test
    fun `system prompt present in vision request`() =
        runTest {
            createPng("sys.png")

            val requestSlot = slot<LlmRequest>()
            coEvery { llmRouter.chat(capture(requestSlot), "test/vision-model") } returns
                LlmResponse(
                    content = "Result",
                    toolCalls = null,
                    usage = TokenUsage(10, 20, 30),
                    finishReason = FinishReason.STOP,
                )

            tool().analyze("sys.png", null)

            val systemMsg = requestSlot.captured.messages[0]
            assertEquals("system", systemMsg.role)
            assertEquals(ImageAnalyzeTool.VISION_SYSTEM_PROMPT, systemMsg.content)
        }

    @Test
    fun `maxTokens from config passed to LLM request`() =
        runTest {
            createPng("tokens.png")

            val requestSlot = slot<LlmRequest>()
            coEvery { llmRouter.chat(capture(requestSlot), "test/vision-model") } returns
                LlmResponse(
                    content = "Result",
                    toolCalls = null,
                    usage = TokenUsage(10, 20, 30),
                    finishReason = FinishReason.STOP,
                )

            tool().analyze("tokens.png", null)

            assertEquals(512, requestSlot.captured.maxTokens)
        }
}
