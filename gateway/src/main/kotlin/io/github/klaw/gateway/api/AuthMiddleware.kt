package io.github.klaw.gateway.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.response.respondText

private const val UNAUTHORIZED_JSON =
    """{"error":"unauthorized","message":"Invalid or missing Bearer token","status":401}"""

fun bearerAuthPlugin(expectedToken: String) =
    createRouteScopedPlugin("BearerAuth") {
        onCall { call ->
            val header = call.request.headers["Authorization"]
            if (header != "Bearer $expectedToken") {
                call.respondText(
                    UNAUTHORIZED_JSON,
                    ContentType.Application.Json,
                    HttpStatusCode.Unauthorized,
                )
            }
        }
    }
