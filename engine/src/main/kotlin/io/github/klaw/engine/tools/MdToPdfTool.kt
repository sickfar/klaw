package io.github.klaw.engine.tools

import io.github.klaw.common.config.DocumentsConfig
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.IOException
import java.nio.file.Files

private val logger = KotlinLogging.logger {}

private const val H1_FONT_SIZE_MULTIPLIER = 1.8f
private const val H2_FONT_SIZE_MULTIPLIER = 1.5f
private const val H3_FONT_SIZE_MULTIPLIER = 1.2f
private const val PAGE_MARGIN = 50f
private const val LINE_HEIGHT_FACTOR = 1.4f
private const val INITIAL_Y_OFFSET = 50f

class MdToPdfTool(
    private val fileTools: FileTools,
    private val config: DocumentsConfig,
) {
    suspend fun convert(
        inputPath: String,
        outputPath: String,
        title: String? = null,
    ): String {
        val inputResolved = fileTools.resolveReadPath(inputPath).getOrElse { return it.message ?: "Access denied" }
        val outputResolved = fileTools.resolveWritePath(outputPath).getOrElse { return it.message ?: "Access denied" }

        return withContext(Dispatchers.VT) {
            convertBlocking(inputResolved, outputResolved, inputPath, outputPath, title)
        }
    }

    private fun convertBlocking(
        inputResolved: java.nio.file.Path,
        outputResolved: java.nio.file.Path,
        displayInputPath: String,
        displayOutputPath: String,
        title: String?,
    ): String {
        if (!Files.exists(inputResolved)) {
            return "Error: file not found: $displayInputPath"
        }

        val mdContent =
            try {
                Files.readString(inputResolved)
            } catch (e: IOException) {
                logger.trace { "md_to_pdf: failed to read input, ${e::class.simpleName}" }
                return "Error: failed to read input file"
            }

        return try {
            val result = renderPdf(mdContent, outputResolved, title)
            val fileSize = Files.size(outputResolved)
            logger.trace { "md_to_pdf: pages=${result.pageCount}, bytes=$fileSize" }
            "OK: PDF generated at $displayOutputPath (${result.pageCount} pages, $fileSize bytes)"
        } catch (e: IOException) {
            logger.trace { "md_to_pdf: render failed, ${e::class.simpleName}" }
            "Error: PDF generation failed"
        }
    }

    private fun renderPdf(
        mdContent: String,
        outputPath: java.nio.file.Path,
        title: String?,
    ): RenderResult {
        val parent = outputPath.parent
        if (parent != null && !Files.exists(parent)) {
            Files.createDirectories(parent)
        }

        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val boldFont = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        val fontSize = config.pdfFontSize
        val pageWidth = PDRectangle.A4.width
        val pageHeight = PDRectangle.A4.height
        val usableWidth = pageWidth - PAGE_MARGIN * 2

        PDDocument().use { doc ->
            if (title != null) {
                doc.documentInformation.title = title
            }

            val renderer = PageRenderer(doc, font, boldFont, fontSize, pageWidth, pageHeight, usableWidth)
            renderer.startNewPage()

            val lines = mdContent.lines()
            for (line in lines) {
                renderer.renderLine(line)
            }

            renderer.finishCurrentPage()
            if (doc.numberOfPages == 0) {
                renderer.startNewPage()
                renderer.finishCurrentPage()
            }

            doc.save(outputPath.toFile())
            return RenderResult(doc.numberOfPages)
        }
    }

    private data class RenderResult(
        val pageCount: Int,
    )

    private class PageRenderer(
        private val doc: PDDocument,
        private val font: PDType1Font,
        private val boldFont: PDType1Font,
        private val baseFontSize: Float,
        private val pageWidth: Float,
        private val pageHeight: Float,
        private val usableWidth: Float,
    ) {
        private var currentStream: PDPageContentStream? = null
        private var yPosition: Float = 0f

        fun startNewPage() {
            finishCurrentPage()
            val page = PDPage(PDRectangle(pageWidth, pageHeight))
            doc.addPage(page)
            currentStream = PDPageContentStream(doc, page)
            yPosition = pageHeight - INITIAL_Y_OFFSET
        }

        fun finishCurrentPage() {
            currentStream?.close()
            currentStream = null
        }

        fun renderLine(line: String) {
            val trimmed = line.trimEnd()
            when {
                trimmed.startsWith("### ") -> renderHeading(trimmed.removePrefix("### "), H3_FONT_SIZE_MULTIPLIER)

                trimmed.startsWith("## ") -> renderHeading(trimmed.removePrefix("## "), H2_FONT_SIZE_MULTIPLIER)

                trimmed.startsWith("# ") -> renderHeading(trimmed.removePrefix("# "), H1_FONT_SIZE_MULTIPLIER)

                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> renderListItem(trimmed.substring(2))

                trimmed.matches(
                    Regex("^\\d+\\.\\s.*"),
                ) -> renderListItem(trimmed.replaceFirst(Regex("^\\d+\\.\\s"), ""))

                trimmed.isEmpty() -> advanceY(baseFontSize * LINE_HEIGHT_FACTOR)

                else -> renderWrappedText(sanitizeText(trimmed), font, baseFontSize)
            }
        }

        private fun renderHeading(
            text: String,
            sizeMultiplier: Float,
        ) {
            val headingSize = baseFontSize * sizeMultiplier
            renderWrappedText(sanitizeText(text), boldFont, headingSize)
        }

        private fun renderListItem(text: String) {
            renderWrappedText("  \u2022 ${sanitizeText(text)}", font, baseFontSize)
        }

        private fun renderWrappedText(
            text: String,
            usedFont: PDType1Font,
            fontSize: Float,
        ) {
            val wrappedLines = wrapText(text, usedFont, fontSize)
            for (wl in wrappedLines) {
                val lineHeight = fontSize * LINE_HEIGHT_FACTOR
                ensureSpace(lineHeight)
                drawText(wl, usedFont, fontSize)
                yPosition -= lineHeight
            }
        }

        private fun ensureSpace(needed: Float) {
            if (yPosition - needed < PAGE_MARGIN) {
                startNewPage()
            }
        }

        private fun advanceY(amount: Float) {
            yPosition -= amount
            if (yPosition < PAGE_MARGIN) {
                startNewPage()
            }
        }

        private fun drawText(
            text: String,
            usedFont: PDType1Font,
            fontSize: Float,
        ) {
            val stream = currentStream ?: return
            stream.beginText()
            stream.setFont(usedFont, fontSize)
            stream.newLineAtOffset(PAGE_MARGIN, yPosition)
            stream.showText(text)
            stream.endText()
        }

        private fun wrapText(
            text: String,
            usedFont: PDType1Font,
            fontSize: Float,
        ): List<String> {
            if (text.isEmpty()) return listOf("")
            val words = text.split(' ')
            val result = mutableListOf<String>()
            val current = StringBuilder()
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                val width = estimateTextWidth(candidate, usedFont, fontSize)
                if (width > usableWidth && current.isNotEmpty()) {
                    result.add(current.toString())
                    current.clear()
                    current.append(word)
                } else {
                    current.clear()
                    current.append(candidate)
                }
            }
            if (current.isNotEmpty()) {
                result.add(current.toString())
            }
            return result.ifEmpty { listOf("") }
        }

        private fun estimateTextWidth(
            text: String,
            usedFont: PDType1Font,
            fontSize: Float,
        ): Float =
            try {
                usedFont.getStringWidth(text) / FONT_UNITS_PER_POINT * fontSize
            } catch (_: IllegalArgumentException) {
                text.length * fontSize * FALLBACK_CHAR_WIDTH_FACTOR
            } catch (_: IOException) {
                text.length * fontSize * FALLBACK_CHAR_WIDTH_FACTOR
            }

        private companion object {
            private const val FONT_UNITS_PER_POINT = 1000f
            private const val FALLBACK_CHAR_WIDTH_FACTOR = 0.6f
        }
    }
}

private const val ASCII_PRINTABLE_START = 0x20
private const val ASCII_PRINTABLE_END = 0x7e

private fun sanitizeText(text: String): String =
    text
        .map { ch ->
            if (ch.code in ASCII_PRINTABLE_START..ASCII_PRINTABLE_END || ch == '\t') ch else ' '
        }.joinToString("")
