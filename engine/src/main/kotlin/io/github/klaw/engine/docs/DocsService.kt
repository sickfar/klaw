package io.github.klaw.engine.docs

interface DocsService {
    suspend fun search(
        query: String,
        topK: Int,
    ): String

    suspend fun read(path: String): String

    suspend fun list(): String
}
