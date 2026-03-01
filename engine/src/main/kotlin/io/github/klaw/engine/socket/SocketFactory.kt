package io.github.klaw.engine.socket

import io.github.klaw.common.paths.KlawPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

private const val DEFAULT_BIND_ADDRESS = "127.0.0.1"

@Factory
class SocketFactory {
    @Singleton
    fun engineSocketServer(handler: SocketMessageHandler): EngineSocketServer {
        val bindAddress = System.getenv("KLAW_ENGINE_BIND") ?: DEFAULT_BIND_ADDRESS
        logger.debug { "Creating EngineSocketServer on $bindAddress:${KlawPaths.enginePort}" }
        return EngineSocketServer(KlawPaths.enginePort, handler, bindAddress = bindAddress)
    }
}
