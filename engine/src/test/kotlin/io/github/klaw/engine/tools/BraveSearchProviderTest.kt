package io.github.klaw.engine.tools

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.klaw.common.config.WebSearchConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BraveSearchProviderTest {
    companion object {
        @JvmStatic
        private lateinit var server: WireMockServer

        @BeforeAll
        @JvmStatic
        fun startServer() {
            server = WireMockServer(wireMockConfig().dynamicPort())
            server.start()
        }

        @AfterAll
        @JvmStatic
        fun stopServer() {
            server.stop()
        }
    }

    @BeforeEach
    fun reset() {
        server.resetAll()
    }

    private fun config(apiKey: String? = "test-key"): WebSearchConfig =
        WebSearchConfig(
            enabled = true,
            provider = "brave",
            apiKey = apiKey,
            maxResults = 5,
            requestTimeoutMs = 5000,
            braveEndpoint = "http://localhost:${server.port()}",
        )

    @Test
    fun `successful search returns parsed results`() =
        runBlocking {
            server.stubFor(
                get(urlPathEqualTo("/res/v1/web/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                """
                                {
                                    "web": {
                                        "results": [
                                            {
                                                "title": "Kotlin Language",
                                                "url": "https://kotlinlang.org",
                                                "description": "Official Kotlin website"
                                            },
                                            {
                                                "title": "Kotlin Docs",
                                                "url": "https://kotlinlang.org/docs",
                                                "description": "Kotlin documentation"
                                            }
                                        ]
                                    }
                                }
                                """.trimIndent(),
                            ),
                    ),
            )

            val provider = BraveSearchProvider(config())
            val results = provider.search("kotlin", 5)

            assertEquals(2, results.size)
            assertEquals("Kotlin Language", results[0].title)
            assertEquals("https://kotlinlang.org", results[0].url)
            assertEquals("Official Kotlin website", results[0].snippet)
            assertEquals("Kotlin Docs", results[1].title)
            assertEquals("https://kotlinlang.org/docs", results[1].url)
            assertEquals("Kotlin documentation", results[1].snippet)

            server.verify(
                getRequestedFor(urlPathEqualTo("/res/v1/web/search"))
                    .withQueryParam("q", equalTo("kotlin"))
                    .withQueryParam("count", equalTo("5"))
                    .withHeader("X-Subscription-Token", equalTo("test-key"))
                    .withHeader("Accept", equalTo("application/json")),
            )
        }

    @Test
    fun `empty results returns empty list`() =
        runBlocking {
            server.stubFor(
                get(urlPathEqualTo("/res/v1/web/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"web": {"results": []}}"""),
                    ),
            )

            val provider = BraveSearchProvider(config())
            val results = provider.search("nonexistent query", 5)

            assertTrue(results.isEmpty())
        }

    @Test
    fun `missing web field returns empty list`() =
        runBlocking {
            server.stubFor(
                get(urlPathEqualTo("/res/v1/web/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"query": {"original": "test"}}"""),
                    ),
            )

            val provider = BraveSearchProvider(config())
            val results = provider.search("test", 5)

            assertTrue(results.isEmpty())
        }

    @Test
    fun `API error 401 throws`() =
        runBlocking {
            server.stubFor(
                get(urlPathEqualTo("/res/v1/web/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(401)
                            .withBody("""{"error": "Unauthorized"}"""),
                    ),
            )

            val provider = BraveSearchProvider(config())
            val ex =
                assertThrows<IllegalStateException> {
                    provider.search("kotlin", 5)
                }
            assertTrue(ex.message!!.contains("401"))
        }

    @Test
    fun `API error 429 rate limit throws`() =
        runBlocking {
            server.stubFor(
                get(urlPathEqualTo("/res/v1/web/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(429)
                            .withBody("""{"error": "Rate limit exceeded"}"""),
                    ),
            )

            val provider = BraveSearchProvider(config())
            val ex =
                assertThrows<IllegalStateException> {
                    provider.search("kotlin", 5)
                }
            assertTrue(ex.message!!.contains("429"))
        }

    @Test
    fun `API error 500 throws`() =
        runBlocking {
            server.stubFor(
                get(urlPathEqualTo("/res/v1/web/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(500)
                            .withBody("""{"error": "Internal server error"}"""),
                    ),
            )

            val provider = BraveSearchProvider(config())
            val ex =
                assertThrows<IllegalStateException> {
                    provider.search("kotlin", 5)
                }
            assertTrue(ex.message!!.contains("500"))
        }

    @Test
    fun `timeout throws`() =
        runBlocking {
            server.stubFor(
                get(urlPathEqualTo("/res/v1/web/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withFixedDelay(10_000),
                    ),
            )

            val provider =
                BraveSearchProvider(
                    config().copy(requestTimeoutMs = 500),
                )

            assertThrows<Exception> {
                provider.search("kotlin", 5)
            }
            Unit
        }

    @Test
    fun `malformed JSON response throws`(): Unit =
        runBlocking {
            server.stubFor(
                get(urlPathEqualTo("/res/v1/web/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("not json at all"),
                    ),
            )

            val provider = BraveSearchProvider(config())
            assertThrows<Exception> {
                provider.search("kotlin", 5)
            }
            Unit
        }

    @Test
    fun `missing API key throws`() =
        runBlocking {
            val provider = BraveSearchProvider(config(apiKey = null))
            val ex =
                assertThrows<IllegalStateException> {
                    provider.search("kotlin", 5)
                }
            assertTrue(ex.message!!.contains("API key"))
        }

    @Test
    fun `query with special characters is URL-encoded`() =
        runBlocking {
            server.stubFor(
                get(urlPathEqualTo("/res/v1/web/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"web": {"results": []}}"""),
                    ),
            )

            val provider = BraveSearchProvider(config())
            provider.search("hello world & more", 3)

            server.verify(
                getRequestedFor(urlPathEqualTo("/res/v1/web/search"))
                    .withQueryParam("q", equalTo("hello world & more"))
                    .withQueryParam("count", equalTo("3")),
            )
        }
}
