package io.github.klaw.gateway.api

import io.github.klaw.common.config.GatewayConfig
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

private const val UNAUTHORIZED_JSON =
    """{"error":"unauthorized","message":"Invalid or missing Bearer token","status":401}"""

@Filter("/api/v1/**")
@Requires(condition = WebuiEnabledCondition::class)
class BearerAuthFilter(
    config: GatewayConfig,
) : HttpServerFilter {
    private val expectedToken: String? = config.webui.apiToken.ifBlank { null }

    override fun doFilter(
        request: HttpRequest<*>,
        chain: ServerFilterChain,
    ): Publisher<MutableHttpResponse<*>> {
        if (expectedToken == null) {
            return chain.proceed(request)
        }
        val header = request.headers["Authorization"]
        if (header != "Bearer $expectedToken") {
            return Flux.just(
                HttpResponse
                    .unauthorized<String>()
                    .body(UNAUTHORIZED_JSON)
                    .contentType(MediaType.APPLICATION_JSON_TYPE),
            )
        }
        return chain.proceed(request)
    }

    override fun getOrder(): Int = POSITION

    companion object {
        const val POSITION = -100
    }
}
