package io.github.klaw.engine.mcp

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.absent
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HttpTransportTest {
    companion object {
        @JvmStatic
        private lateinit var wireMock: WireMockServer

        @BeforeAll
        @JvmStatic
        fun startWireMock() {
            wireMock = WireMockServer(wireMockConfig().dynamicPort())
            wireMock.start()
        }

        @AfterAll
        @JvmStatic
        fun stopWireMock() {
            wireMock.stop()
        }
    }

    @BeforeEach
    fun reset() {
        wireMock.resetAll()
    }

    @Test
    fun `successful request-response cycle`() =
        runBlocking {
            val responseBody = """{"jsonrpc":"2.0","id":1,"result":{}}"""
            wireMock.stubFor(
                post(urlEqualTo("/mcp"))
                    .willReturn(aResponse().withStatus(200).withBody(responseBody)),
            )

            val transport = HttpTransport("http://localhost:${wireMock.port()}/mcp")
            val request = """{"jsonrpc":"2.0","id":1,"method":"initialize"}"""
            transport.send(request)
            val result = transport.receive()

            assertEquals(responseBody, result)
        }

    @Test
    fun `sends content-type application json`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/mcp"))
                    .willReturn(aResponse().withStatus(200).withBody("{}")),
            )

            val transport = HttpTransport("http://localhost:${wireMock.port()}/mcp")
            transport.send("{}")
            transport.receive()

            wireMock.verify(
                postRequestedFor(urlEqualTo("/mcp"))
                    .withHeader("Content-Type", equalTo("application/json")),
            )
        }

    @Test
    fun `sends authorization header when apiKey provided`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/mcp"))
                    .willReturn(aResponse().withStatus(200).withBody("{}")),
            )

            val transport = HttpTransport("http://localhost:${wireMock.port()}/mcp", apiKey = "test-key")
            transport.send("{}")
            transport.receive()

            wireMock.verify(
                postRequestedFor(urlEqualTo("/mcp"))
                    .withHeader("Authorization", equalTo("Bearer test-key")),
            )
        }

    @Test
    fun `no authorization header when apiKey is null`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/mcp"))
                    .willReturn(aResponse().withStatus(200).withBody("{}")),
            )

            val transport = HttpTransport("http://localhost:${wireMock.port()}/mcp")
            transport.send("{}")
            transport.receive()

            wireMock.verify(
                postRequestedFor(urlEqualTo("/mcp"))
                    .withHeader("Authorization", absent()),
            )
        }

    @Test
    fun `throws exception on http 500`() =
        runBlocking {
            wireMock.stubFor(
                post(urlEqualTo("/mcp"))
                    .willReturn(aResponse().withStatus(500).withBody("Internal Server Error")),
            )

            val transport = HttpTransport("http://localhost:${wireMock.port()}/mcp")
            transport.send("{}")

            assertThrows<HttpTransportException> {
                runBlocking { transport.receive() }
            }
            Unit
        }

    @Test
    fun `isOpen always returns true`() {
        val transport = HttpTransport("http://localhost:${wireMock.port()}/mcp")
        assertTrue(transport.isOpen)
    }
}
