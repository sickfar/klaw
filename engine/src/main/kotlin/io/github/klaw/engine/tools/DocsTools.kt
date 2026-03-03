package io.github.klaw.engine.tools

import io.github.klaw.engine.docs.DocsService
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class DocsTools(
    private val docsService: DocsService,
) {
    suspend fun search(
        query: String,
        topK: Int = 5,
    ): String {
        logger.trace { "docs_search: topK=$topK" }
        return docsService.search(query, topK)
    }

    suspend fun read(path: String): String {
        logger.trace { "docs_read: path=$path" }
        return docsService.read(path)
    }

    suspend fun list(): String {
        logger.trace { "docs_list" }
        return docsService.list()
    }
}
