package io.github.klaw.engine.tools

import io.github.klaw.common.config.VisionConfig
import io.github.klaw.common.llm.ImageUrlContentPart
import io.github.klaw.common.llm.ImageUrlData
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.TextContentPart
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

private val EXTENSION_TO_MIME =
    mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
    )

class ImageAnalyzeTool(
    private val llmRouter: LlmRouter,
    private val config: VisionConfig,
    private val allowedPaths: List<Path>,
) {
    @Suppress("TooGenericExceptionCaught", "ReturnCount")
    suspend fun analyze(
        path: String,
        prompt: String?,
    ): String {
        if (config.model.isBlank()) {
            return "Error: vision model not configured"
        }

        val safePath =
            resolveSafePath(path)
                ?: return "Error: Access denied: path outside allowed directories"

        if (!Files.exists(safePath)) {
            return "Error: file not found: $path"
        }

        val extension = path.substringAfterLast('.', "").lowercase()
        val mimeType =
            EXTENSION_TO_MIME[extension]
                ?: return "Error: unsupported image format '.$extension'"

        if (mimeType !in config.supportedFormats) {
            return "Error: unsupported image format '$mimeType'"
        }

        val fileSize = withContext(Dispatchers.VT) { Files.size(safePath) }
        if (fileSize > config.maxImageSizeBytes) {
            return "Error: image too large ($fileSize bytes, max ${config.maxImageSizeBytes})"
        }

        val bytes = withContext(Dispatchers.VT) { Files.readAllBytes(safePath) }
        val base64 = Base64.getEncoder().encodeToString(bytes)
        val dataUrl = "data:$mimeType;base64,$base64"

        val effectivePrompt = prompt ?: DEFAULT_PROMPT

        val request =
            LlmRequest(
                messages =
                    listOf(
                        LlmMessage(role = "system", content = VISION_SYSTEM_PROMPT),
                        LlmMessage(
                            role = "user",
                            contentParts =
                                listOf(
                                    TextContentPart(effectivePrompt),
                                    ImageUrlContentPart(ImageUrlData(dataUrl)),
                                ),
                        ),
                    ),
                maxTokens = config.maxTokens,
            )

        logger.debug { "image_analyze: mimeType=$mimeType, fileSize=$fileSize" }

        return try {
            val response = llmRouter.chat(request, config.model)
            response.content ?: "Error: vision model returned empty response"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(e) { "image_analyze failed" }
            "Error: ${e::class.simpleName}"
        }
    }

    /**
     * Validates the image path and returns (absolutePath, mimeType) if valid, null if invalid.
     * Used by ToolRegistryImpl to build inline image markers without calling the vision model.
     */
    fun resolveAndValidate(path: String): Pair<String, String>? {
        val safePath = resolveSafePath(path) ?: return null
        if (!Files.exists(safePath)) return null

        val extension = path.substringAfterLast('.', "").lowercase()
        val mimeType = EXTENSION_TO_MIME[extension] ?: return null
        if (mimeType !in config.supportedFormats) return null

        val fileSize = Files.size(safePath)
        if (fileSize > config.maxImageSizeBytes) return null

        return safePath.toAbsolutePath().toString() to mimeType
    }

    private fun resolveSafePath(userPath: String): Path? =
        allowedPaths.firstNotNullOfOrNull { base ->
            val resolved = base.resolve(userPath).normalize()
            resolved.takeIf { isWithinBase(it, base) }
        }

    @Suppress("TooGenericExceptionCaught")
    private fun isWithinBase(
        resolved: Path,
        base: Path,
    ): Boolean {
        if (!resolved.startsWith(base)) return false
        val symlinkOk = !Files.isSymbolicLink(resolved) || isRealPathWithin(resolved, base)
        val existsOk = !Files.exists(resolved) || isRealPathWithin(resolved, base)
        return symlinkOk && existsOk
    }

    @Suppress("TooGenericExceptionCaught")
    private fun isRealPathWithin(
        path: Path,
        base: Path,
    ): Boolean =
        try {
            path.toRealPath().startsWith(base.toRealPath())
        } catch (_: Exception) {
            false
        }

    companion object {
        const val DEFAULT_PROMPT = "Describe this image in detail"

        internal const val VISION_SYSTEM_PROMPT =
            """You are an expert image analyst. Provide a thorough, detailed description of the image.

Describe the following aspects:
- All visible objects, people, animals, and their spatial arrangement
- Any text, numbers, labels, or writing visible in the image — transcribe exactly
- Colors, lighting conditions, and visual style
- Layout, composition, and perspective
- Actions, events, or interactions taking place
- Background elements and environmental context
- Charts, diagrams, tables, or data visualizations if present — describe their structure and content
- UI elements, screenshots, or code if the image shows a screen

Be factual, precise, and comprehensive. Do not speculate about what is not visible.
Respond in the same language as the user's prompt."""
    }
}
