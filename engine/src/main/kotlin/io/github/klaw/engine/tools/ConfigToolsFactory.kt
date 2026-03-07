package io.github.klaw.engine.tools

import io.github.klaw.common.paths.KlawPaths
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class ConfigToolsFactory {
    @Singleton
    fun configTools(shutdownController: ShutdownController): ConfigTools =
        ConfigTools(KlawPaths.config, shutdownController)
}
