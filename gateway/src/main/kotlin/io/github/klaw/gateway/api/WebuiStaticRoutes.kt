package io.github.klaw.gateway.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import jakarta.inject.Singleton

@Singleton
class WebuiStaticRoutes {
    private val spaFallbackHtml: ByteArray? by lazy {
        javaClass.classLoader.getResourceAsStream("webui/200.html")?.readBytes()
            ?: javaClass.classLoader.getResourceAsStream("webui/index.html")?.readBytes()
    }

    fun install(routing: Route) {
        // Serve static assets from classpath (JS, CSS, images, pre-rendered pages)
        routing.staticResources("/", "webui")

        // SPA fallback: any GET that didn't match a static file or API route
        routing.get("{...}") {
            val html = spaFallbackHtml
            if (html != null) {
                call.respondBytes(html, ContentType.Text.Html, HttpStatusCode.OK)
            } else {
                call.respondBytes(
                    "Web UI not available".toByteArray(),
                    ContentType.Text.Plain,
                    HttpStatusCode.NotFound,
                )
            }
        }
    }
}
