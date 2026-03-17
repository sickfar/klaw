package io.github.klaw.engine.socket

import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton

private const val RANDOM_PORT = 0

@Factory
@Replaces(factory = SocketFactory::class)
class TestSocketFactory {
    @Singleton
    @Replaces(bean = EngineOutboundBuffer::class, factory = SocketFactory::class)
    fun engineOutboundBuffer(): EngineOutboundBuffer {
        val tmpDir = System.getProperty("java.io.tmpdir")
        return EngineOutboundBuffer("$tmpDir/klaw-test-engine-outbound-buffer.jsonl")
    }

    @Singleton
    @Replaces(bean = EngineSocketServer::class, factory = SocketFactory::class)
    fun engineSocketServer(
        handler: SocketMessageHandler,
        outboundBuffer: EngineOutboundBuffer,
    ): EngineSocketServer = EngineSocketServer(RANDOM_PORT, handler, outboundBuffer = outboundBuffer)
}
