package io.github.klaw.gateway.api

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.WebuiConfig
import io.micronaut.http.HttpHeaders
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpStatus
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuthStatusControllerTest {
    private fun buildController(apiToken: String): AuthStatusController {
        val config =
            mockk<GatewayConfig> {
                every { webui } returns WebuiConfig(enabled = true, apiToken = apiToken)
            }
        return AuthStatusController(config)
    }

    private fun mockRequest(authHeader: String? = null): HttpRequest<*> {
        val headers =
            mockk<HttpHeaders> {
                every { get(any<CharSequence>()) } returns authHeader
            }
        return mockk {
            every { this@mockk.headers } returns headers
        }
    }

    @Test
    fun `returns authenticated true when correct Bearer token provided`() {
        val controller = buildController("my-secret-token")
        val request = mockRequest("Bearer my-secret-token")

        val response = controller.check(request)

        assertEquals(HttpStatus.OK, response.status)
        val body = response.body()!!
        assertEquals(true, body["authenticated"])
        assertEquals(true, body["authRequired"])
    }

    @Test
    fun `returns authenticated false when wrong Bearer token provided`() {
        val controller = buildController("my-secret-token")
        val request = mockRequest("Bearer wrong-token")

        val response = controller.check(request)

        assertEquals(HttpStatus.OK, response.status)
        val body = response.body()!!
        assertEquals(false, body["authenticated"])
        assertEquals(true, body["authRequired"])
    }

    @Test
    fun `returns authenticated false when no Bearer token provided`() {
        val controller = buildController("my-secret-token")
        val request = mockRequest()

        val response = controller.check(request)

        assertEquals(HttpStatus.OK, response.status)
        val body = response.body()!!
        assertEquals(false, body["authenticated"])
        assertEquals(true, body["authRequired"])
    }

    @Test
    fun `returns authenticated true and authRequired false when apiToken is blank`() {
        val controller = buildController("")
        val request = mockRequest()

        val response = controller.check(request)

        assertEquals(HttpStatus.OK, response.status)
        val body = response.body()!!
        assertEquals(true, body["authenticated"])
        assertEquals(false, body["authRequired"])
    }
}
