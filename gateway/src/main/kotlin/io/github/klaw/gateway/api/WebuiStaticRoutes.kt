package io.github.klaw.gateway.api

import io.ktor.server.http.content.staticResources
import io.ktor.server.routing.Route
import jakarta.inject.Singleton

@Singleton
class WebuiStaticRoutes {
    fun install(routing: Route) {
        routing.staticResources("/", "webui") {
            default("index.html")
        }
    }
}
