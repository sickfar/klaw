package io.github.klaw.engine.tools

import io.github.klaw.common.config.DocumentsConfig
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.text.PDFTextStripper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

class PdfReadTool(
    private val fileTools: FileTools,
    private val config: DocumentsConfig,
) {
    suspend fun read(
        path: String,
        startPage: Int? = null,
        endPage: Int? = null,
    ): String {
        val safePath = fileTools.resolveReadPath(path).getOrElse { return it.message ?: "Access denied" }
        return withContext(Dispatchers.VT) {
            readBlocking(safePath, path, startPage, endPage)
        }
    }

    private fun readBlocking(
        safePath: Path,
        displayPath: String,
        startPage: Int?,
        endPage: Int?,
    ): String {
        val validationError = validateFile(safePath, displayPath)
        if (validationError != null) return validationError

        return when (val loaded = loadDocument(safePath)) {
            is LoadResult.Error -> loaded.message
            is LoadResult.Success -> loaded.doc.use { extractText(it, displayPath, startPage, endPage) }
        }
    }

    private fun validateFile(
        safePath: Path,
        displayPath: String,
    ): String? {
        if (!Files.exists(safePath)) return "Error: file not found: $displayPath"
        val fileSize = Files.size(safePath)
        if (fileSize > config.maxPdfSizeBytes) {
            return "Error: file size ($fileSize bytes) exceeds maximum allowed (${config.maxPdfSizeBytes} bytes)"
        }
        return null
    }

    private sealed class LoadResult {
        data class Success(
            val doc: PDDocument,
        ) : LoadResult()

        data class Error(
            val message: String,
        ) : LoadResult()
    }

    private fun loadDocument(safePath: Path): LoadResult =
        try {
            LoadResult.Success(Loader.loadPDF(safePath.toFile()))
        } catch (e: org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException) {
            logger.trace { "pdf_read: encrypted PDF, ${e::class.simpleName}" }
            LoadResult.Error("Error: PDF is password-protected or encrypted")
        } catch (e: IOException) {
            logger.trace { "pdf_read: corrupted/invalid PDF, ${e::class.simpleName}" }
            LoadResult.Error("Error: invalid or corrupted PDF file")
        }

    private fun extractText(
        doc: PDDocument,
        displayPath: String,
        startPage: Int?,
        endPage: Int?,
    ): String {
        val totalPages = doc.numberOfPages
        val effectiveStart = startPage ?: 1
        val effectiveEnd = endPage ?: totalPages

        if (effectiveStart < 1 || effectiveStart > totalPages) {
            return "Error: start_page $effectiveStart is out of range (1-$totalPages)"
        }
        if (effectiveEnd < effectiveStart || effectiveEnd > totalPages) {
            return "Error: end_page $effectiveEnd is out of range ($effectiveStart-$totalPages)"
        }

        val maxPageLimit = computeMaxPages(effectiveStart, effectiveEnd)
        val lastPage = (effectiveStart + maxPageLimit - 1).coerceAtMost(effectiveEnd)
        val truncatedByMaxPages = lastPage < effectiveEnd

        val output = buildPageOutput(doc, displayPath, totalPages, effectiveStart, lastPage)

        logger.trace { "pdf_read: pages=${lastPage - effectiveStart + 1}, bytes=${output.length}" }

        return applyTruncation(output, truncatedByMaxPages, totalPages)
    }

    private fun computeMaxPages(
        effectiveStart: Int,
        effectiveEnd: Int,
    ): Int {
        val requestedPages = effectiveEnd - effectiveStart + 1
        return if (config.maxPages > 0) {
            requestedPages.coerceAtMost(config.maxPages)
        } else {
            requestedPages
        }
    }

    private fun buildPageOutput(
        doc: PDDocument,
        displayPath: String,
        totalPages: Int,
        effectiveStart: Int,
        lastPage: Int,
    ): String {
        val stripper = PDFTextStripper()
        return buildString {
            append("File: ").append(displayPath).append('\n')
            append("Pages: ").append(effectiveStart).append('-').append(lastPage)
            append(" of ").append(totalPages).append('\n')
            append("---\n")
            for (page in effectiveStart..lastPage) {
                stripper.startPage = page
                stripper.endPage = page
                val pageText = stripper.getText(doc)
                append("--- Page ").append(page).append(" ---\n")
                append(pageText)
                if (!pageText.endsWith('\n')) {
                    append('\n')
                }
            }
        }
    }

    private fun applyTruncation(
        output: String,
        truncatedByMaxPages: Boolean,
        totalPages: Int,
    ): String {
        if (output.length > config.maxOutputChars) {
            return output.substring(0, config.maxOutputChars) +
                "\n[Output truncated at ${config.maxOutputChars} characters]"
        }
        if (truncatedByMaxPages) {
            val shown = config.maxPages
            return output +
                "[Showing $shown of $totalPages pages — limit reached]"
        }
        return output
    }
}
