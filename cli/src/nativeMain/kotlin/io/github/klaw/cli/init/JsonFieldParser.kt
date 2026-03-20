package io.github.klaw.cli.init

private const val ID_KEY_OFFSET = 4 // length of "\"id\"" to skip to the value

/**
 * Parses model IDs from an OpenAI-compatible /models response JSON.
 * Returns empty list if json is null or malformed.
 *
 * Example: `{"data":[{"id":"glm-5"},{"id":"glm-4-plus"}]}` → `["glm-5", "glm-4-plus"]`
 */
internal fun parseModels(json: String?): List<String> {
    if (json == null) return emptyList()
    val arrayContent = extractDataArrayContent(json) ?: return emptyList()
    val result = mutableListOf<String>()
    var searchFrom = 0
    while (true) {
        val idKey = arrayContent.indexOf("\"id\"", searchFrom)
        if (idKey < 0) break
        val (modelId, nextPos) = parseOneModelId(arrayContent, idKey + ID_KEY_OFFSET) ?: break
        if (modelId.isNotEmpty()) result += modelId
        searchFrom = nextPos
    }
    return result
}

private fun extractDataArrayContent(json: String): String? {
    val dataStart = json.indexOf("\"data\"")
    if (dataStart < 0) return null
    val arrayStart = json.indexOf('[', dataStart)
    if (arrayStart < 0) return null
    val arrayEnd = json.indexOf(']', arrayStart)
    if (arrayEnd < 0) return null
    return json.substring(arrayStart + 1, arrayEnd)
}

private fun parseOneModelId(
    content: String,
    startIdx: Int,
): Pair<String, Int>? {
    var i = startIdx
    while (i < content.length && (content[i] == ':' || content[i] == ' ')) i++
    if (i >= content.length || content[i] != '"') return null
    i++ // skip opening quote
    val sb = StringBuilder()
    while (i < content.length) {
        val c = content[i]
        if (c == '\\' && i + 1 < content.length && content[i + 1] == '"') {
            sb.append('"')
            i += 2
        } else if (c == '"') {
            break
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString() to i + 1
}

/** Returns (unescaped char, new index after escape sequence). */
private fun unescapeChar(
    json: String,
    backslashIdx: Int,
): Pair<Char, Int> {
    val next = if (backslashIdx + 1 < json.length) json[backslashIdx + 1] else '\\'
    val ch =
        when (next) {
            'n' -> '\n'
            '"' -> '"'
            '\\' -> '\\'
            'r' -> '\r'
            't' -> '\t'
            else -> next
        }
    return ch to backslashIdx + 2
}

internal fun extractJsonField(
    json: String,
    field: String,
): String? {
    val keyPattern = """"$field""""
    var i = json.indexOf(keyPattern)
    if (i < 0) return null
    i += keyPattern.length
    // skip whitespace and colon
    while (i < json.length && (json[i] == ':' || json[i] == ' ')) i++
    if (i >= json.length || json[i] != '"') return null
    i++ // skip opening quote
    val sb = StringBuilder()
    while (i < json.length) {
        val c = json[i]
        if (c == '\\' && i + 1 < json.length) {
            val (ch, next) = unescapeChar(json, i)
            sb.append(ch)
            i = next
        } else if (c == '"') {
            break
        } else {
            sb.append(c)
            i++
        }
    }
    return sb.toString()
}
