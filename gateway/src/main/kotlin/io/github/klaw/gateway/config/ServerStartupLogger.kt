package io.github.klaw.gateway.config

import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.runtime.server.event.ServerStartupEvent
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class ServerStartupLogger : ApplicationEventListener<ServerStartupEvent> {
    override fun onApplicationEvent(event: ServerStartupEvent) {
        val port = event.source.port
        logger.info { "Micronaut HTTP server started on port $port" }
    }
}
