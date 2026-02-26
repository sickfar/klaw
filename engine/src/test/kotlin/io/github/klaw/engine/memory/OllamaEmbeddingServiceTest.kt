package io.github.klaw.engine.memory

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class OllamaEmbeddingServiceTest {
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

    private fun service(): OllamaEmbeddingService =
        OllamaEmbeddingService(
            baseUrl = "http://localhost:${wireMock.port()}",
        )

    @Test
    fun `single embed returns 384d vector`() =
        runBlocking {
            val embedding = (1..384).map { it * 0.001f }
            val embeddingJson = embedding.joinToString(",") { it.toString() }
            wireMock.stubFor(
                post(urlEqualTo("/api/embed"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"embeddings":[[$embeddingJson]]}"""),
                    ),
            )

            val result = service().embed("hello world")
            assertEquals(384, result.size)
            assertEquals(0.001f, result[0], 0.0001f)
            assertEquals(0.384f, result[383], 0.0001f)
        }

    @Test
    fun `batch embed returns multiple vectors`() =
        runBlocking {
            val emb1 = (1..384).map { it * 0.001f }.joinToString(",") { it.toString() }
            val emb2 = (1..384).map { it * 0.002f }.joinToString(",") { it.toString() }
            wireMock.stubFor(
                post(urlEqualTo("/api/embed"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""{"embeddings":[[$emb1],[$emb2]]}"""),
                    ),
            )

            val results = service().embedBatch(listOf("hello", "world"))
            assertEquals(2, results.size)
            assertEquals(384, results[0].size)
            assertEquals(384, results[1].size)
            assertEquals(0.001f, results[0][0], 0.0001f)
            assertEquals(0.002f, results[1][0], 0.0001f)
        }

    @Test
    fun `server error throws exception`() {
        wireMock.stubFor(
            post(urlEqualTo("/api/embed"))
                .willReturn(
                    aResponse()
                        .withStatus(500)
                        .withBody("Internal Server Error"),
                ),
        )

        assertThrows<RuntimeException> {
            runBlocking {
                service().embed("fail")
            }
        }
    }
}
