package io.github.klaw.gateway.socket

import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class GatewaySocketFactory {
    @Singleton
    fun gatewayBuffer(): GatewayBuffer = GatewayBuffer(KlawPaths.gatewayBuffer)

    @Singleton
    fun engineSocketClient(
        buffer: GatewayBuffer,
        handler: OutboundMessageHandler,
    ): EngineSocketClient = EngineSocketClient(KlawPaths.engineHost, KlawPaths.enginePort, buffer, handler)

    @Singleton
    fun conversationJsonlWriter(): ConversationJsonlWriter = ConversationJsonlWriter(KlawPaths.conversations)
}
