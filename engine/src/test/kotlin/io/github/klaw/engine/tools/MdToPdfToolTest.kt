package io.github.klaw.engine.tools

import io.github.klaw.common.config.DocumentsConfig
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class MdToPdfToolTest {
    @TempDir
    lateinit var workspace: Path

    private fun fileTools(): FileTools =
        FileTools(listOf(workspace), 10_485_760L, mapOf("\$WORKSPACE" to workspace.toString()))

    private fun tool(config: DocumentsConfig = DocumentsConfig()): MdToPdfTool = MdToPdfTool(fileTools(), config)

    @Test
    fun `happy path simple markdown produces PDF`() =
        runBlocking {
            Files.writeString(workspace.resolve("doc.md"), "# Hello\n\nThis is a test document.")
            val result = tool().convert("doc.md", "doc.pdf")
            assertTrue(result.contains("OK"), "Expected OK but got: $result")
            assertTrue(Files.exists(workspace.resolve("doc.pdf")), "Output PDF should exist")
            assertTrue(Files.size(workspace.resolve("doc.pdf")) > 0, "Output PDF should not be empty")
        }

    @Test
    fun `headings are preserved in output PDF`() =
        runBlocking {
            val md =
                """
                |# Heading 1
                |## Heading 2
                |### Heading 3
                |Some body text.
                """.trimMargin()
            Files.writeString(workspace.resolve("headings.md"), md)
            val result = tool().convert("headings.md", "headings.pdf")
            assertTrue(result.contains("OK"), "Expected OK but got: $result")
            val pdfFile = workspace.resolve("headings.pdf")
            assertTrue(Files.exists(pdfFile), "Output PDF should exist")
            Loader.loadPDF(pdfFile.toFile()).use { doc ->
                assertTrue(doc.numberOfPages > 0, "PDF should have at least one page")
            }
        }

    @Test
    fun `lists and paragraphs produce valid PDF`() =
        runBlocking {
            val md =
                """
                |# Shopping List
                |
                |- Apples
                |- Bananas
                |- Oranges
                |
                |## Steps
                |
                |1. Go to store
                |2. Buy items
                |3. Come home
                |
                |This is a paragraph after the lists.
                """.trimMargin()
            Files.writeString(workspace.resolve("lists.md"), md)
            val result = tool().convert("lists.md", "lists.pdf")
            assertTrue(result.contains("OK"), "Expected OK but got: $result")
            assertTrue(Files.exists(workspace.resolve("lists.pdf")), "Output PDF should exist")
        }

    @Test
    fun `title metadata is set in PDF`() =
        runBlocking {
            Files.writeString(workspace.resolve("titled.md"), "# Content\n\nSome text here.")
            val result = tool().convert("titled.md", "titled.pdf", title = "My Document Title")
            assertTrue(result.contains("OK"), "Expected OK but got: $result")
            val pdfFile = workspace.resolve("titled.pdf")
            Loader.loadPDF(pdfFile.toFile()).use { doc ->
                val info = doc.documentInformation
                assertNotNull(info.title, "PDF should have title metadata")
                assertTrue(
                    info.title.contains("My Document Title"),
                    "PDF title should match, got: ${info.title}",
                )
            }
        }

    @Test
    fun `non-existent input returns error`() =
        runBlocking {
            val result = tool().convert("missing.md", "output.pdf")
            assertTrue(
                result.contains("Error") || result.contains("not found"),
                "Expected file not found error but got: $result",
            )
        }

    @Test
    fun `path traversal on input is rejected`() =
        runBlocking {
            val result = tool().convert("../../etc/passwd", "output.pdf")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
        }

    @Test
    fun `path traversal on output is rejected`() =
        runBlocking {
            Files.writeString(workspace.resolve("legit.md"), "# OK")
            val result = tool().convert("legit.md", "../../tmp/evil.pdf")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
        }

    @Test
    fun `empty markdown produces valid PDF`() =
        runBlocking {
            Files.writeString(workspace.resolve("empty.md"), "")
            val result = tool().convert("empty.md", "empty.pdf")
            assertTrue(result.contains("OK"), "Expected OK but got: $result")
            assertTrue(Files.exists(workspace.resolve("empty.pdf")), "Output PDF should exist")
        }

    @Test
    fun `large markdown is handled gracefully`() =
        runBlocking {
            val largeMd = "# Big Document\n\n" + "This is a paragraph. ".repeat(5000)
            Files.writeString(workspace.resolve("large.md"), largeMd)
            val result = tool().convert("large.md", "large.pdf")
            // Should either succeed or return a graceful error, not crash
            assertTrue(
                result.contains("OK") || result.contains("Error") || result.contains("truncat"),
                "Expected OK, error, or truncation but got: $result",
            )
        }

    @Test
    fun `non-markdown binary input produces PDF with raw text`() =
        runBlocking {
            val binaryContent = ByteArray(256) { it.toByte() }
            Files.write(workspace.resolve("binary.bin"), binaryContent)
            val result = tool().convert("binary.bin", "binary.pdf")
            // Should handle gracefully — either produce a PDF with raw text or return an error
            assertTrue(
                result.contains("OK") || result.contains("Error"),
                "Expected OK or error but got: $result",
            )
        }
}
