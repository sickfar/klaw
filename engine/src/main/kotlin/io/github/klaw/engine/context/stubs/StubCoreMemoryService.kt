package io.github.klaw.engine.context.stubs

import io.github.klaw.engine.context.CoreMemoryService
import jakarta.inject.Singleton

@Singleton
class StubCoreMemoryService : CoreMemoryService {
    override suspend fun load(): String = ""
}
