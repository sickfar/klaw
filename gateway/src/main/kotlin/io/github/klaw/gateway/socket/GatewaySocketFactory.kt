package io.github.klaw.gateway.socket

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.ApplicationContext
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Factory
class GatewaySocketFactory {
    @Singleton
    fun gatewayBuffer(): GatewayBuffer = GatewayBuffer(KlawPaths.gatewayBuffer)

    @Singleton
    fun engineSocketClient(
        buffer: GatewayBuffer,
        handler: OutboundMessageHandler,
        config: GatewayConfig,
        applicationContext: ApplicationContext,
    ): EngineSocketClient =
        EngineSocketClient(
            host = KlawPaths.engineHost,
            port = KlawPaths.enginePort,
            buffer = buffer,
            outboundHandler = handler,
            maxReconnectAttempts = config.delivery.maxReconnectAttempts,
            drainBudgetMs = config.delivery.drainBudgetSeconds.toLong() * MILLIS_PER_SECOND,
            onReconnectExhausted = {
                logger.error { "Engine reconnect exhausted, shutting down gateway" }
                applicationContext.close()
            },
        )

    companion object {
        private const val MILLIS_PER_SECOND = 1000L
    }

    @Singleton
    fun conversationJsonlWriter(): ConversationJsonlWriter = ConversationJsonlWriter(KlawPaths.conversations)
}
