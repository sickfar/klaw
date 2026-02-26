package io.github.klaw.engine.memory

import io.github.klaw.common.util.approximateTokenCount
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MarkdownChunkerTest {
    private val chunker = MarkdownChunker(chunkSize = 400, overlap = 80)

    @Test
    fun `empty input returns no chunks`() {
        assertEquals(emptyList<MemoryChunk>(), chunker.chunk(""))
        assertEquals(emptyList<MemoryChunk>(), chunker.chunk("   "))
    }

    @Test
    fun `short text returns single chunk`() {
        val text = "Hello world, this is a short paragraph."
        val chunks = chunker.chunk(text)
        assertEquals(1, chunks.size)
        assertEquals(text, chunks[0].content)
        assertEquals("chunker", chunks[0].source)
    }

    @Test
    fun `basic chunking respects ~400 token limit`() {
        // Build a long text with many paragraphs
        val paragraph =
            "The quick brown fox jumps over the lazy dog. " +
                "This sentence is here to add more tokens to the paragraph. " +
                "We need enough text to exceed the chunk size limit. " +
                "Adding more words to make this paragraph reasonably long."
        val text = (1..30).joinToString("\n\n") { "Paragraph $it. $paragraph" }

        val chunks = chunker.chunk(text)
        assertTrue(chunks.size > 1, "Expected multiple chunks, got ${chunks.size}")

        for (chunk in chunks) {
            val tokens = approximateTokenCount(chunk.content)
            // Allow some tolerance: chunk should not massively exceed chunkSize
            // (only code blocks or force-split paragraphs may slightly exceed)
            assertTrue(
                tokens <= 500,
                "Chunk has $tokens tokens, expected roughly <= 400+overlap",
            )
        }
    }

    @Test
    fun `overlap verification`() {
        val paragraph =
            "The quick brown fox jumps over the lazy dog. " +
                "This sentence is here to add more tokens to the paragraph. " +
                "We need enough text to exceed the chunk size limit. " +
                "Adding more words to make this paragraph reasonably long."
        val text = (1..30).joinToString("\n\n") { "Paragraph $it. $paragraph" }

        val chunks = chunker.chunk(text)
        assertTrue(chunks.size >= 2, "Need at least 2 chunks for overlap test")

        // Last ~overlap tokens of chunk N should appear at start of chunk N+1
        for (i in 0 until chunks.size - 1) {
            val currentContent = chunks[i].content
            val nextContent = chunks[i + 1].content
            // The overlap text from end of current chunk should be a prefix of next chunk
            // We check that next chunk starts with some trailing portion of current chunk
            val currentLines = currentContent.lines()
            val nextLines = nextContent.lines()
            // At least one line from end of current should appear at start of next
            val lastLinesOfCurrent = currentLines.takeLast(3).map { it.trim() }.filter { it.isNotEmpty() }
            val firstLinesOfNext = nextLines.take(5).map { it.trim() }.filter { it.isNotEmpty() }
            val hasOverlap =
                lastLinesOfCurrent.any { line ->
                    firstLinesOfNext.any { it.contains(line) || line.contains(it) }
                }
            assertTrue(hasOverlap, "Chunk ${i + 1} should overlap with chunk $i")
        }
    }

    @Test
    fun `header preservation`() {
        val text =
            buildString {
                appendLine("# Main Title")
                appendLine()
                appendLine("Some intro text.")
                appendLine()
                appendLine("## Section A")
                appendLine()
                // Enough text to produce multiple chunks within this section
                repeat(50) {
                    appendLine(
                        "Section A content line $it with some extra words to fill tokens" +
                            " and make this paragraph longer than before.",
                    )
                    appendLine()
                }
                appendLine("## Section B")
                appendLine()
                appendLine("Section B content.")
            }

        val chunks = chunker.chunk(text)
        // Find chunks that are clearly in Section A (after the first chunk)
        val sectionAChunks = chunks.filter { it.sectionHeader?.contains("Section A") == true }
        assertTrue(sectionAChunks.isNotEmpty(), "Should have chunks with Section A header")

        val sectionBChunks = chunks.filter { it.sectionHeader?.contains("Section B") == true }
        assertTrue(sectionBChunks.isNotEmpty(), "Should have chunks with Section B header")
    }

    @Test
    fun `code block preservation`() {
        val codeBlock =
            buildString {
                appendLine("```kotlin")
                repeat(15) { appendLine("    val x$it = computeSomething($it)") }
                appendLine("```")
            }
        val text = "Some intro text.\n\n$codeBlock\nSome outro text."

        val chunks = chunker.chunk(text)
        // The code block should appear intact in exactly one chunk
        val codeContent = codeBlock.trim()
        val chunksWithCode = chunks.filter { it.content.contains("```kotlin") }
        assertEquals(1, chunksWithCode.size, "Code block should be in exactly one chunk")
        assertTrue(
            chunksWithCode[0].content.contains(codeContent),
            "Code block should not be split",
        )
    }

    @Test
    fun `list grouping`() {
        val list = (1..10).joinToString("\n") { "- Item $it with some description text" }
        val text = "Introduction paragraph.\n\n$list\n\nConclusion paragraph."

        val chunks = chunker.chunk(text)
        // All list items should be in the same chunk (they're small enough)
        val chunksWithItems = chunks.filter { it.content.contains("- Item 1") }
        assertEquals(1, chunksWithItems.size)
        for (i in 1..10) {
            assertTrue(
                chunksWithItems[0].content.contains("- Item $i"),
                "Item $i should be grouped with other items",
            )
        }
    }

    @Test
    fun `very long paragraph force-split`() {
        // Single paragraph with no blank lines, exceeding chunkSize
        val longParagraph = (1..200).joinToString(". ") { "Sentence number $it with extra words" } + "."

        val tokens = approximateTokenCount(longParagraph)
        assertTrue(tokens > 400, "Test paragraph should exceed chunk size, got $tokens tokens")

        val chunks = chunker.chunk(longParagraph)
        assertTrue(chunks.size > 1, "Long paragraph should be force-split into multiple chunks")

        // Reassemble (minus overlaps) should cover all content
        // Each chunk should end roughly at a sentence boundary
        for (chunk in chunks.dropLast(1)) {
            val content = chunk.content.trimEnd()
            assertTrue(
                content.endsWith(".") || content.endsWith(".\n"),
                "Force-split chunks should end at sentence boundaries: '${content.takeLast(20)}'",
            )
        }
    }
}
