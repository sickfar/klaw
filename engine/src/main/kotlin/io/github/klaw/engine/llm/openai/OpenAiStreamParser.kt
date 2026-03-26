package io.github.klaw.engine.llm.openai

import kotlinx.serialization.json.Json

private val streamJson =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

object OpenAiStreamParser {
    private const val DATA_PREFIX = "data: "
    private const val DONE_MARKER = "[DONE]"

    fun parseSseLine(line: String): OpenAiStreamChunk? {
        if (!line.startsWith(DATA_PREFIX)) return null
        val payload = line.removePrefix(DATA_PREFIX).trim()
        if (payload == DONE_MARKER) return null
        return streamJson.decodeFromString<OpenAiStreamChunk>(payload)
    }
}
