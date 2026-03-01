package io.github.klaw.engine.socket

import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton

private const val RANDOM_PORT = 0

@Factory
@Replaces(factory = SocketFactory::class)
class TestSocketFactory {
    @Singleton
    @Replaces(bean = EngineSocketServer::class, factory = SocketFactory::class)
    fun engineSocketServer(handler: SocketMessageHandler): EngineSocketServer = EngineSocketServer(RANDOM_PORT, handler)
}
