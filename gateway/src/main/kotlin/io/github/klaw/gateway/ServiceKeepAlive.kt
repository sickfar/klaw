package io.github.klaw.gateway

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.channel.ChatWebSocketEndpoint
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.micronaut.context.ApplicationContext
import io.micronaut.runtime.ApplicationConfiguration
import io.micronaut.runtime.EmbeddedApplication
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class ServiceKeepAlive(
    private val applicationContext: ApplicationContext,
    private val applicationConfiguration: ApplicationConfiguration,
    private val chatEndpoint: ChatWebSocketEndpoint,
    private val config: GatewayConfig,
) : EmbeddedApplication<ServiceKeepAlive> {
    @Volatile
    private var running = false
    private var server: EmbeddedServer<*, *>? = null

    override fun getApplicationContext(): ApplicationContext = applicationContext

    override fun getApplicationConfiguration(): ApplicationConfiguration = applicationConfiguration

    override fun isRunning(): Boolean = running

    override fun isServer(): Boolean = true

    override fun start(): ServiceKeepAlive {
        running = true
        if (config.channels.console?.enabled == true) {
            val port = config.channels.console?.port ?: DEFAULT_CONSOLE_PORT
            server =
                embeddedServer(CIO, port = port) {
                    install(WebSockets)
                    routing { chatEndpoint.install(this) }
                }.start(wait = false)
            logger.info { "Ktor server started on port $port" }
        }
        return this
    }

    override fun stop(): ServiceKeepAlive {
        server?.stop(GRACE_MS, TIMEOUT_MS)
        running = false
        logger.info { "ServiceKeepAlive stopped" }
        return this
    }

    companion object {
        private const val DEFAULT_CONSOLE_PORT = 37474
        private const val GRACE_MS = 500L
        private const val TIMEOUT_MS = 1000L
    }
}
