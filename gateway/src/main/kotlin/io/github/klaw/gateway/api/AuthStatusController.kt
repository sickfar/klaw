package io.github.klaw.gateway.api

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.config.WebuiEnabledCondition
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Get

@Controller("/api/v1/auth")
@Requires(condition = WebuiEnabledCondition::class)
class AuthStatusController(
    config: GatewayConfig,
) {
    private val expectedToken: String? = config.webui.apiToken.ifBlank { null }
    private val authRequired = expectedToken != null

    @Get("/check")
    fun check(request: HttpRequest<*>): HttpResponse<Map<String, Any>> {
        val authenticated =
            if (expectedToken == null) {
                true
            } else {
                val header = request.headers["Authorization"]
                header == "Bearer $expectedToken"
            }
        return HttpResponse.ok(
            mapOf(
                "authenticated" to authenticated,
                "authRequired" to authRequired,
            ),
        )
    }
}
