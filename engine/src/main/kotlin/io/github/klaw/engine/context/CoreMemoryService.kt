package io.github.klaw.engine.context

interface CoreMemoryService {
    suspend fun load(): String
}
