package io.github.klaw.engine.tools

import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.socket.EngineSocketServer
import io.micronaut.context.annotation.Factory
import jakarta.inject.Provider
import jakarta.inject.Singleton

@Factory
class ConfigToolsFactory {
    @Singleton
    fun configTools(
        shutdownController: ShutdownController,
        socketServerProvider: Provider<EngineSocketServer>,
    ): ConfigTools = ConfigTools(KlawPaths.config, shutdownController, socketServerProvider)
}
