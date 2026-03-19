package io.github.klaw.engine.context

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.context.stubs.StubWorkspaceLoader
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.memory.MemoryServiceImpl
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
        config: EngineConfig,
    ): KlawWorkspaceLoader {
        val loader =
            KlawWorkspaceLoader(
                workspacePath = Path.of(KlawPaths.workspace),
                memoryService = memoryService,
                config = config,
            )
        if (memoryService is MemoryServiceImpl) {
            memoryService.setOnSaveCallback {
                loader.refreshMemorySummary()
            }
        }
        loader.initialize()
        return loader
    }
}
