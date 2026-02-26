package io.github.klaw.engine.context

import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.context.stubs.StubCoreMemoryService
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton
import java.nio.file.Path

@Factory
class FileCoreMemoryServiceFactory {
    @Singleton
    @Replaces(StubCoreMemoryService::class)
    fun fileCoreMemoryService(): FileCoreMemoryService = FileCoreMemoryService(Path.of(KlawPaths.coreMemory))
}
