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
        val workspace = Path.of(KlawPaths.workspace)
        return FileTools(workspace, config.files.maxFileSizeBytes)
    }
}
