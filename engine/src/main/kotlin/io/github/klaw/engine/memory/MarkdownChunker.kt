package io.github.klaw.engine.memory

import io.github.klaw.common.util.approximateTokenCount

class MarkdownChunker(
    private val chunkSize: Int = 400,
    private val overlap: Int = 80,
) {
    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ReturnCount", "LoopWithTooManyJumpStatements")
    fun chunk(text: String): List<MemoryChunk> {
        if (text.isBlank()) return emptyList()

        val blocks = parseBlocks(text)
        if (blocks.isEmpty()) return emptyList()

        val chunks = mutableListOf<MemoryChunk>()
        var currentBlocks = mutableListOf<String>()
        var currentTokens = 0
        var currentHeader: String? = null
        var overlapText: String? = null

        fun emitChunk() {
            if (currentBlocks.isEmpty()) return
            val content =
                buildString {
                    if (overlapText != null && chunks.isNotEmpty()) {
                        append(overlapText)
                        append("\n\n")
                    }
                    append(currentBlocks.joinToString("\n\n"))
                }.trim()
            if (content.isNotEmpty()) {
                chunks.add(MemoryChunk(content = content, source = "chunker", sectionHeader = currentHeader))
            }
            // Compute overlap from the tail of content
            overlapText = computeOverlapText(content)
            currentBlocks = mutableListOf()
            currentTokens = 0
        }

        for (block in blocks) {
            // Track section headers
            if (block.startsWith("#")) {
                currentHeader = block.trim()
            }

            val blockTokens = approximateTokenCount(block)

            // If adding this block exceeds chunkSize and we have content, emit first
            if (currentTokens + blockTokens > chunkSize && currentBlocks.isNotEmpty()) {
                emitChunk()
            }

            // Handle blocks that alone exceed chunkSize
            if (blockTokens > chunkSize && !block.trimStart().startsWith("```")) {
                // Force-split long paragraph at sentence boundaries
                val sentences = splitSentences(block)
                var sentenceGroup = mutableListOf<String>()
                var groupTokens = 0

                for (sentence in sentences) {
                    val sTokens = approximateTokenCount(sentence)
                    if (groupTokens + sTokens > chunkSize && sentenceGroup.isNotEmpty()) {
                        currentBlocks.add(sentenceGroup.joinToString(""))
                        currentTokens = approximateTokenCount(currentBlocks.joinToString("\n\n"))
                        emitChunk()
                        sentenceGroup = mutableListOf()
                        groupTokens = 0
                    }
                    sentenceGroup.add(sentence)
                    groupTokens += sTokens
                }
                if (sentenceGroup.isNotEmpty()) {
                    currentBlocks.add(sentenceGroup.joinToString(""))
                    currentTokens += groupTokens
                }
            } else {
                currentBlocks.add(block)
                currentTokens += blockTokens
            }
        }

        emitChunk()
        return chunks
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "ComplexCondition", "LoopWithTooManyJumpStatements")
    private fun parseBlocks(text: String): List<String> {
        val blocks = mutableListOf<String>()
        val lines = text.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Code block
            if (line.trimStart().startsWith("```")) {
                val codeLines = mutableListOf(line)
                i++
                while (i < lines.size) {
                    codeLines.add(lines[i])
                    if (lines[i].trimStart().startsWith("```") && codeLines.size > 1) {
                        i++
                        break
                    }
                    i++
                }
                blocks.add(codeLines.joinToString("\n"))
                continue
            }

            // Header line
            if (line.startsWith("#")) {
                blocks.add(line)
                i++
                continue
            }

            // List block: group consecutive list items
            if (isListItem(line)) {
                val listLines = mutableListOf(line)
                i++
                while (i < lines.size &&
                    (isListItem(lines[i]) || (lines[i].startsWith("  ") && listLines.isNotEmpty()))
                ) {
                    listLines.add(lines[i])
                    i++
                }
                blocks.add(listLines.joinToString("\n"))
                continue
            }

            // Blank line — skip
            if (line.isBlank()) {
                i++
                continue
            }

            // Regular paragraph: collect until blank line
            val paraLines = mutableListOf(line)
            i++
            while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("#") &&
                !lines[i].trimStart().startsWith("```") && !isListItem(lines[i])
            ) {
                paraLines.add(lines[i])
                i++
            }
            blocks.add(paraLines.joinToString("\n"))
        }

        return blocks
    }

    private fun isListItem(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.startsWith("- ") || trimmed.startsWith("* ") ||
            trimmed.matches(Regex("""^\d+\.\s.*"""))
    }

    private fun splitSentences(text: String): List<String> {
        // Split at ". " boundaries, keeping the delimiter with the preceding sentence
        val result = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            val idx = remaining.indexOf(". ")
            if (idx == -1) {
                result.add(remaining)
                break
            }
            result.add(remaining.substring(0, idx + 2))
            remaining = remaining.substring(idx + 2)
        }
        return result
    }

    @Suppress("ReturnCount")
    private fun computeOverlapText(content: String): String? {
        if (content.isEmpty()) return null
        // Take trailing text worth ~overlap tokens
        val words = content.split(Regex("\\s+"))
        if (words.size <= 1) return null

        // If the entire content is shorter than overlap, skip overlap
        if (approximateTokenCount(content) <= overlap) return null

        // Work backwards to find ~overlap tokens worth of text
        var startIdx = words.size
        for (i in words.indices.reversed()) {
            val tokenCount = approximateTokenCount(words.subList(i, words.size).joinToString(" "))
            if (tokenCount >= overlap) {
                startIdx = i
                break
            }
        }
        val overlapWords = words.subList(startIdx, words.size)
        return if (overlapWords.isNotEmpty()) overlapWords.joinToString(" ") else null
    }
}
