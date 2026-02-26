package io.github.klaw.engine.tools.stubs

import io.github.klaw.engine.docs.DocsService
import jakarta.inject.Singleton

@Singleton
class StubDocsService : DocsService {
    override suspend fun search(
        query: String,
        topK: Int,
    ): String = "Docs service not yet implemented"

    override suspend fun read(path: String): String = "Docs service not yet implemented"

    override suspend fun list(): String = "Docs service not yet implemented"
}
