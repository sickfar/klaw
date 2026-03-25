package io.github.klaw.gateway.api

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.WebuiConfig
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MutableHttpResponse
import io.micronaut.http.filter.ServerFilterChain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import reactor.core.publisher.Flux
import java.util.Optional

class WebSocketAuthFilterTest {
    private fun buildFilter(apiToken: String): WebSocketAuthFilter {
        val config =
            mockk<GatewayConfig> {
                every { webui } returns WebuiConfig(enabled = true, apiToken = apiToken)
            }
        return WebSocketAuthFilter(config)
    }

    private fun mockRequest(tokenParam: String?): HttpRequest<*> =
        mockk {
            every { parameters } returns
                mockk {
                    every { getFirst("token") } returns
                        if (tokenParam != null) Optional.of(tokenParam) else Optional.empty()
                }
        }

    private fun mockChain(request: HttpRequest<*>): ServerFilterChain {
        val okResponse: MutableHttpResponse<*> = HttpResponse.ok<String>()
        return mockk {
            every { proceed(request) } returns Flux.just(okResponse)
        }
    }

    @Test
    fun `no apiToken configured -- proceeds chain`() {
        val filter = buildFilter("")
        val request = mockRequest(null)
        val chain = mockChain(request)

        filter.doFilter(request, chain)

        verify { chain.proceed(request) }
    }

    @Test
    fun `correct token in query parameter -- proceeds chain`() {
        val filter = buildFilter("secret")
        val request = mockRequest("secret")
        val chain = mockChain(request)

        filter.doFilter(request, chain)

        verify { chain.proceed(request) }
    }

    @Test
    fun `wrong token -- returns 401`() {
        val filter = buildFilter("secret")
        val request = mockRequest("wrong")
        val chain = mockChain(request)

        val result = Flux.from(filter.doFilter(request, chain)).blockFirst()!!

        assertEquals(HttpStatus.UNAUTHORIZED, result.status)
        verify(exactly = 0) { chain.proceed(any()) }
    }

    @Test
    fun `missing token param -- returns 401`() {
        val filter = buildFilter("secret")
        val request = mockRequest(null)
        val chain = mockChain(request)

        val result = Flux.from(filter.doFilter(request, chain)).blockFirst()!!

        assertEquals(HttpStatus.UNAUTHORIZED, result.status)
        verify(exactly = 0) { chain.proceed(any()) }
    }

    @Test
    fun `empty token param -- returns 401`() {
        val filter = buildFilter("secret")
        val request = mockRequest("")
        val chain = mockChain(request)

        val result = Flux.from(filter.doFilter(request, chain)).blockFirst()!!

        assertEquals(HttpStatus.UNAUTHORIZED, result.status)
        verify(exactly = 0) { chain.proceed(any()) }
    }
}
