package io.github.klaw.engine.context

import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.context.stubs.StubWorkspaceLoader
import io.github.klaw.engine.memory.MemoryService
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton
import java.nio.file.Path

@Factory
class KlawWorkspaceLoaderFactory {
    @Singleton
    @Replaces(StubWorkspaceLoader::class)
    fun klawWorkspaceLoader(
        memoryService: MemoryService,
        coreMemory: CoreMemoryService,
    ): KlawWorkspaceLoader {
        val loader =
            KlawWorkspaceLoader(
                workspacePath = Path.of(KlawPaths.workspace),
                memoryService = memoryService,
                coreMemory = coreMemory,
            )
        loader.initialize()
        return loader
    }
}
