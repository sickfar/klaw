package io.github.klaw.engine.tools

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
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

class TavilySearchProviderTest {
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

    private fun config(apiKey: String? = "test-tavily-key"): WebSearchConfig =
        WebSearchConfig(
            enabled = true,
            provider = "tavily",
            apiKey = apiKey,
            maxResults = 5,
            requestTimeoutMs = 5000,
            tavilyEndpoint = "http://localhost:${server.port()}",
        )

    @Test
    fun `successful search returns parsed results`() =
        runBlocking {
            server.stubFor(
                post(urlPathEqualTo("/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(
                                """
                                {
                                    "results": [
                                        {
                                            "title": "Kotlin Language",
                                            "url": "https://kotlinlang.org",
                                            "content": "Official Kotlin website with docs and tutorials"
                                        },
                                        {
                                            "title": "Kotlin Playground",
                                            "url": "https://play.kotlinlang.org",
                                            "content": "Try Kotlin in the browser"
                                        }
                                    ]
                                }
                                """.trimIndent(),
                            ),
                    ),
            )

            val provider = TavilySearchProvider(config())
            val results = provider.search("kotlin", 5)

            assertEquals(2, results.size)
            assertEquals("Kotlin Language", results[0].title)
            assertEquals("https://kotlinlang.org", results[0].url)
            assertEquals("Official Kotlin website with docs and tutorials", results[0].snippet)
            assertEquals("Kotlin Playground", results[1].title)
            assertEquals("https://play.kotlinlang.org", results[1].url)
            assertEquals("Try Kotlin in the browser", results[1].snippet)

            server.verify(
                postRequestedFor(urlPathEqualTo("/search"))
                    .withRequestBody(
                        equalToJson(
                            """
                            {
                                "api_key": "test-tavily-key",
                                "query": "kotlin",
                                "max_results": 5,
                                "include_answer": false
                            }
                            """.trimIndent(),
                        ),
                    ),
            )
        }

    @Test
    fun `empty results returns empty list`() =
        runBlocking {
            server.stubFor(
                post(urlPathEqualTo("/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"results": []}"""),
                    ),
            )

            val provider = TavilySearchProvider(config())
            val results = provider.search("nonexistent query", 5)

            assertTrue(results.isEmpty())
        }

    @Test
    fun `missing results field returns empty list`() =
        runBlocking {
            server.stubFor(
                post(urlPathEqualTo("/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"answer": "some answer"}"""),
                    ),
            )

            val provider = TavilySearchProvider(config())
            val results = provider.search("test", 5)

            assertTrue(results.isEmpty())
        }

    @Test
    fun `API error 401 throws`() =
        runBlocking {
            server.stubFor(
                post(urlPathEqualTo("/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(401)
                            .withBody("""{"error": "Invalid API key"}"""),
                    ),
            )

            val provider = TavilySearchProvider(config())
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
                post(urlPathEqualTo("/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(429)
                            .withBody("""{"error": "Rate limit"}"""),
                    ),
            )

            val provider = TavilySearchProvider(config())
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
                post(urlPathEqualTo("/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(500)
                            .withBody("""{"error": "Server error"}"""),
                    ),
            )

            val provider = TavilySearchProvider(config())
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
                post(urlPathEqualTo("/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withFixedDelay(10_000),
                    ),
            )

            val provider =
                TavilySearchProvider(
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
                post(urlPathEqualTo("/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("not json"),
                    ),
            )

            val provider = TavilySearchProvider(config())
            assertThrows<Exception> {
                provider.search("kotlin", 5)
            }
            Unit
        }

    @Test
    fun `missing API key throws`() =
        runBlocking {
            val provider = TavilySearchProvider(config(apiKey = null))
            val ex =
                assertThrows<IllegalStateException> {
                    provider.search("kotlin", 5)
                }
            assertTrue(ex.message!!.contains("API key"))
        }

    @Test
    fun `maxResults parameter is sent in request body`() =
        runBlocking {
            server.stubFor(
                post(urlPathEqualTo("/search"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"results": []}"""),
                    ),
            )

            val provider = TavilySearchProvider(config())
            provider.search("test query", 3)

            server.verify(
                postRequestedFor(urlPathEqualTo("/search"))
                    .withRequestBody(
                        equalToJson(
                            """
                            {
                                "api_key": "test-tavily-key",
                                "query": "test query",
                                "max_results": 3,
                                "include_answer": false
                            }
                            """.trimIndent(),
                        ),
                    ),
            )
        }
}
