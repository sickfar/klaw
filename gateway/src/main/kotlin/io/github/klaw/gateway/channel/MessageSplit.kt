package io.github.klaw.gateway.channel

private const val TELEGRAM_MAX_MESSAGE_LENGTH = 4096

/**
 * Splits a text message into chunks that fit within [maxLength].
 *
 * Split priority:
 * 1. Paragraph boundary (`\n\n`) within limit
 * 2. Line boundary (`\n`) within limit
 * 3. Hard cut at [maxLength]
 */
fun splitMessage(
    text: String,
    maxLength: Int = TELEGRAM_MAX_MESSAGE_LENGTH,
): List<String> {
    if (text.length <= maxLength) return listOf(text)

    val chunks = mutableListOf<String>()
    var remaining = text

    while (remaining.isNotEmpty()) {
        if (remaining.length <= maxLength) {
            chunks.add(remaining)
            break
        }

        val splitIndex = findSplitIndex(remaining, maxLength)
        chunks.add(remaining.substring(0, splitIndex).trimEnd())
        remaining = remaining.substring(splitIndex).trimStart('\n')
    }

    return chunks
}

private fun findSplitIndex(
    text: String,
    maxLength: Int,
): Int {
    // Try paragraph boundary
    val paragraphIndex = text.lastIndexOf("\n\n", maxLength)
    if (paragraphIndex > 0) return paragraphIndex

    // Try line boundary
    val lineIndex = text.lastIndexOf('\n', maxLength)
    if (lineIndex > 0) return lineIndex

    // Hard cut
    return maxLength
}
