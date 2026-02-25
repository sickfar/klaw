package io.github.klaw.engine.socket

import io.github.klaw.common.paths.KlawPaths
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class SocketFactory {
    @Singleton
    @Suppress("MaxLineLength")
    fun engineSocketServer(handler: SocketMessageHandler): EngineSocketServer = EngineSocketServer(KlawPaths.engineSocket, handler)
}
