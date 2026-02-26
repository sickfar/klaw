package io.github.klaw.engine.tools

import io.github.klaw.engine.docs.DocsService
import jakarta.inject.Singleton

@Singleton
class DocsTools(
    private val docsService: DocsService,
) {
    suspend fun search(
        query: String,
        topK: Int = 5,
    ): String = docsService.search(query, topK)

    suspend fun read(path: String): String = docsService.read(path)

    suspend fun list(): String = docsService.list()
}
