package io.github.klaw.gateway.api

import io.github.klaw.gateway.config.WebuiEnabledCondition
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.annotation.Filter
import io.micronaut.http.filter.HttpServerFilter
import io.micronaut.http.filter.ServerFilterChain
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux

@Filter("/**")
@Requires(condition = WebuiEnabledCondition::class)
class SpaFallbackFilter : HttpServerFilter {
    private val fallbackHtml: String? =
        javaClass.classLoader
            .getResourceAsStream("webui/index.html")
            ?.use { it.bufferedReader().readText() }

    override fun doFilter(
        request: HttpRequest<*>,
        chain: ServerFilterChain,
    ): Publisher<MutableHttpResponse<*>> {
        val path = request.path

        if (fallbackHtml == null || shouldSkip(path)) {
            return chain.proceed(request)
        }

        if (isSpaRoute(path)) {
            return Flux.just(
                HttpResponse
                    .ok(fallbackHtml)
                    .contentType(MediaType.TEXT_HTML_TYPE),
            )
        }

        return chain.proceed(request)
    }

    override fun getOrder(): Int = POSITION

    companion object {
        const val POSITION = 100

        private val SKIP_PREFIXES = listOf("/api/", "/_nuxt/", "/upload", "/ws/")
        private val SKIP_EXACT = setOf("/api", "/health")

        fun shouldSkip(path: String): Boolean {
            if (SKIP_EXACT.contains(path)) return true
            if (SKIP_PREFIXES.any { path.startsWith(it) }) return true
            val lastSegment = path.substringAfterLast('/')
            return lastSegment.contains('.')
        }

        private fun isSpaRoute(path: String): Boolean = !shouldSkip(path)
    }
}
