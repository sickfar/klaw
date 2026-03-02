package io.github.klaw.engine.tools

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.nio.file.Path

@Factory
class FileToolsFactory {
    @Singleton
    fun fileTools(config: EngineConfig): FileTools {
        val allowedPaths =
            listOf(
                Path.of(KlawPaths.workspace), // First = workspace (writable)
                Path.of(KlawPaths.state),
                Path.of(KlawPaths.data),
                Path.of(KlawPaths.config),
                Path.of(KlawPaths.cache),
            )
        return FileTools(allowedPaths, config.files.maxFileSizeBytes)
    }
}
