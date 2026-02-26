package io.github.klaw.engine.context

interface CoreMemoryService {
    suspend fun load(): String

    suspend fun getJson(): String

    suspend fun update(
        section: String,
        key: String,
        value: String,
    ): String

    suspend fun delete(
        section: String,
        key: String,
    ): String
}
