package io.github.klaw.engine.tools

import io.github.klaw.common.config.DocumentsConfig
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

@Suppress("LongMethod")
class PdfReadToolTest {
    @TempDir
    lateinit var workspace: Path

    private fun fileTools(): FileTools =
        FileTools(listOf(workspace), 10_485_760L, mapOf("\$WORKSPACE" to workspace.toString()))

    private fun tool(config: DocumentsConfig = DocumentsConfig()): PdfReadTool = PdfReadTool(fileTools(), config)

    private fun createPdf(
        dir: Path,
        name: String,
        pages: List<String>,
    ): Path {
        val file = dir.resolve(name)
        PDDocument().use { doc ->
            for (text in pages) {
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(50f, 700f)
                    cs.showText(text)
                    cs.endText()
                }
            }
            doc.save(file.toFile())
        }
        return file
    }

    private fun createEncryptedPdf(
        dir: Path,
        name: String,
    ): Path {
        val file = dir.resolve(name)
        PDDocument().use { doc ->
            val page = PDPage()
            doc.addPage(page)
            doc.protect(StandardProtectionPolicy("owner", "user", AccessPermission()))
            doc.save(file.toFile())
        }
        return file
    }

    @Test
    fun `happy path single page PDF returns text with page marker`() =
        runBlocking {
            createPdf(workspace, "single.pdf", listOf("Hello World"))
            val result = tool().read("single.pdf")
            assertTrue(result.contains("--- Page 1 ---"), "Expected page marker but got: $result")
            assertTrue(result.contains("Hello World"), "Expected text content but got: $result")
        }

    @Test
    fun `multi-page PDF returns all page markers and text`() =
        runBlocking {
            createPdf(workspace, "multi.pdf", listOf("Page one text", "Page two text", "Page three text"))
            val result = tool().read("multi.pdf")
            assertTrue(result.contains("--- Page 1 ---"), "Expected page 1 marker")
            assertTrue(result.contains("--- Page 2 ---"), "Expected page 2 marker")
            assertTrue(result.contains("--- Page 3 ---"), "Expected page 3 marker")
            assertTrue(result.contains("Page one text"), "Expected page 1 text")
            assertTrue(result.contains("Page two text"), "Expected page 2 text")
            assertTrue(result.contains("Page three text"), "Expected page 3 text")
        }

    @Test
    fun `page range start-end returns only requested pages`() =
        runBlocking {
            createPdf(
                workspace,
                "five.pdf",
                listOf("First", "Second", "Third", "Fourth", "Fifth"),
            )
            val result = tool().read("five.pdf", startPage = 2, endPage = 3)
            assertTrue(result.contains("--- Page 2 ---"), "Expected page 2 marker")
            assertTrue(result.contains("--- Page 3 ---"), "Expected page 3 marker")
            assertTrue(result.contains("Second"), "Expected page 2 text")
            assertTrue(result.contains("Third"), "Expected page 3 text")
            assertTrue(!result.contains("First"), "Should not contain page 1 text")
            assertTrue(!result.contains("Fourth"), "Should not contain page 4 text")
            assertTrue(!result.contains("Fifth"), "Should not contain page 5 text")
        }

    @Test
    fun `page range single page returns only that page`() =
        runBlocking {
            createPdf(workspace, "three.pdf", listOf("Alpha", "Beta", "Gamma"))
            val result = tool().read("three.pdf", startPage = 2, endPage = 2)
            assertTrue(result.contains("--- Page 2 ---"), "Expected page 2 marker")
            assertTrue(result.contains("Beta"), "Expected page 2 text")
            assertTrue(!result.contains("Alpha"), "Should not contain page 1 text")
            assertTrue(!result.contains("Gamma"), "Should not contain page 3 text")
        }

    @Test
    fun `page range out of bounds returns error`() =
        runBlocking {
            createPdf(workspace, "short.pdf", listOf("One", "Two", "Three"))
            val result = tool().read("short.pdf", startPage = 10, endPage = 15)
            assertTrue(
                result.contains("Error") || result.contains("out of range") || result.contains("invalid"),
                "Expected error for out-of-bounds pages but got: $result",
            )
        }

    @Test
    fun `path traversal is rejected`() =
        runBlocking {
            val result = tool().read("../../etc/passwd")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
        }

    @Test
    fun `absolute path outside workspace is rejected`() =
        runBlocking {
            val result = tool().read("/etc/passwd")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
        }

    @Test
    fun `non-existent file returns error`() =
        runBlocking {
            val result = tool().read("missing.pdf")
            assertTrue(
                result.contains("Error") || result.contains("not found"),
                "Expected file not found error but got: $result",
            )
        }

    @Test
    fun `non-PDF file returns error about invalid format`() =
        runBlocking {
            Files.writeString(workspace.resolve("readme.txt"), "This is plain text")
            val result = tool().read("readme.txt")
            assertTrue(
                result.contains("Error") || result.contains("invalid") || result.contains("corrupted"),
                "Expected invalid/corrupted PDF error but got: $result",
            )
        }

    @Test
    fun `empty PDF returns content with page info`() =
        runBlocking {
            // PDF with a page but no text
            val file = workspace.resolve("empty.pdf")
            PDDocument().use { doc ->
                doc.addPage(PDPage())
                doc.save(file.toFile())
            }
            val result = tool().read("empty.pdf")
            assertTrue(result.contains("--- Page 1 ---"), "Expected page marker for empty page")
        }

    @Test
    fun `file too large returns size error`() =
        runBlocking {
            createPdf(workspace, "large.pdf", listOf("Some content"))
            val smallConfig = DocumentsConfig(maxPdfSizeBytes = 100L)
            val result = PdfReadTool(fileTools(), smallConfig).read("large.pdf")
            assertTrue(
                result.contains("exceeds") || result.contains("too large") || result.contains("size"),
                "Expected size error but got: $result",
            )
        }

    @Test
    fun `max pages limit truncates output`() =
        runBlocking {
            createPdf(
                workspace,
                "many.pdf",
                listOf("P1", "P2", "P3", "P4", "P5"),
            )
            val limitedConfig = DocumentsConfig(maxPages = 2)
            val result = PdfReadTool(fileTools(), limitedConfig).read("many.pdf")
            assertTrue(result.contains("--- Page 1 ---"), "Expected page 1 marker")
            assertTrue(result.contains("--- Page 2 ---"), "Expected page 2 marker")
            assertTrue(!result.contains("--- Page 3 ---"), "Should not contain page 3")
            assertTrue(
                result.contains("truncat") || result.contains("limit") || result.contains("more pages"),
                "Expected truncation note but got: $result",
            )
        }

    @Test
    fun `max output chars truncates output with note`() =
        runBlocking {
            createPdf(workspace, "wordy.pdf", listOf("A".repeat(100)))
            val smallOutputConfig = DocumentsConfig(maxOutputChars = 50)
            val result = PdfReadTool(fileTools(), smallOutputConfig).read("wordy.pdf")
            assertTrue(
                result.contains("truncat") || result.contains("limit"),
                "Expected truncation note but got: $result",
            )
            assertTrue(result.length <= 200, "Output should be reasonably bounded, got ${result.length} chars")
        }

    @Test
    fun `password-protected PDF returns error`() =
        runBlocking {
            createEncryptedPdf(workspace, "locked.pdf")
            val result = tool().read("locked.pdf")
            assertTrue(
                result.contains("Error") || result.contains("password") || result.contains("encrypted"),
                "Expected password/encrypted error but got: $result",
            )
        }
}
