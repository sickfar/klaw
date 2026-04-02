package io.github.klaw.engine.memory

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.http.Fault
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.net.http.HttpClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

class ModelDownloaderTest {
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

        private const val MODEL_PATH = "/intfloat/multilingual-e5-small/resolve/main/onnx/model.onnx"
        private const val TOKENIZER_PATH = "/intfloat/multilingual-e5-small/resolve/main/tokenizer.json"
    }

    @TempDir
    lateinit var tempDir: Path

    @BeforeEach
    fun reset() {
        wireMock.resetAll()
    }

    private fun downloader(modelDir: Path = tempDir): ModelDownloader {
        val client =
            HttpClient
                .newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(5))
                .build()
        return ModelDownloader(
            modelDir = modelDir,
            baseUrl = "http://localhost:${wireMock.port()}",
            httpClient = client,
        )
    }

    @Test
    fun `files already exist -- no download attempted`() {
        Files.write(tempDir.resolve("model.onnx"), byteArrayOf(1, 2, 3))
        Files.write(tempDir.resolve("tokenizer.json"), byteArrayOf(4, 5, 6))

        val result = downloader().ensureModelFiles()

        assertTrue(result)
        wireMock.verify(0, getRequestedFor(urlEqualTo(MODEL_PATH)))
        wireMock.verify(0, getRequestedFor(urlEqualTo(TOKENIZER_PATH)))
    }

    @Test
    fun `downloads both files when missing`() {
        val modelContent = "fake-onnx-model-data".toByteArray()
        val tokenizerContent = """{"key":"value"}""".toByteArray()

        wireMock.stubFor(
            get(urlEqualTo(MODEL_PATH))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withBody(modelContent),
                ),
        )
        wireMock.stubFor(
            get(urlEqualTo(TOKENIZER_PATH))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withBody(tokenizerContent),
                ),
        )

        val result = downloader().ensureModelFiles()

        assertTrue(result)
        assertTrue(Files.exists(tempDir.resolve("model.onnx")))
        assertTrue(Files.exists(tempDir.resolve("tokenizer.json")))
        assertArrayEquals(modelContent, Files.readAllBytes(tempDir.resolve("model.onnx")))
        assertArrayEquals(tokenizerContent, Files.readAllBytes(tempDir.resolve("tokenizer.json")))

        // no leftover tmp files
        val tmpFiles = Files.list(tempDir).use { it.filter { p -> p.toString().endsWith(".tmp") }.count() }
        assertEquals(0, tmpFiles)
    }

    @Test
    fun `creates directories if they dont exist`() {
        val nestedDir = tempDir.resolve("nested").resolve("deep").resolve("model-dir")

        wireMock.stubFor(
            get(urlEqualTo(MODEL_PATH))
                .willReturn(aResponse().withStatus(200).withBody("model-data")),
        )
        wireMock.stubFor(
            get(urlEqualTo(TOKENIZER_PATH))
                .willReturn(aResponse().withStatus(200).withBody("tokenizer-data")),
        )

        val result = downloader(nestedDir).ensureModelFiles()

        assertTrue(result)
        assertTrue(Files.exists(nestedDir.resolve("model.onnx")))
        assertTrue(Files.exists(nestedDir.resolve("tokenizer.json")))
    }

    @Test
    fun `skips existing file downloads missing one`() {
        Files.write(tempDir.resolve("model.onnx"), "existing-model".toByteArray())

        wireMock.stubFor(
            get(urlEqualTo(TOKENIZER_PATH))
                .willReturn(aResponse().withStatus(200).withBody("new-tokenizer")),
        )

        val result = downloader().ensureModelFiles()

        assertTrue(result)
        // model was not downloaded
        wireMock.verify(0, getRequestedFor(urlEqualTo(MODEL_PATH)))
        // tokenizer was downloaded
        wireMock.verify(1, getRequestedFor(urlEqualTo(TOKENIZER_PATH)))
        // existing model content preserved
        assertEquals("existing-model", Files.readString(tempDir.resolve("model.onnx")))
        assertEquals("new-tokenizer", Files.readString(tempDir.resolve("tokenizer.json")))
    }

    @Test
    fun `returns false on HTTP 404`() {
        wireMock.stubFor(
            get(urlEqualTo(MODEL_PATH))
                .willReturn(aResponse().withStatus(404)),
        )

        val result = downloader().ensureModelFiles()

        assertFalse(result)
        assertFalse(Files.exists(tempDir.resolve("model.onnx")))
    }

    @Test
    fun `returns false on network error`() {
        wireMock.stubFor(
            get(urlEqualTo(MODEL_PATH))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)),
        )

        val result = downloader().ensureModelFiles()

        assertFalse(result)
    }

    @Test
    fun `cleans up temp file on failure`() {
        wireMock.stubFor(
            get(urlEqualTo(MODEL_PATH))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withFault(Fault.RANDOM_DATA_THEN_CLOSE),
                ),
        )

        val result = downloader().ensureModelFiles()

        assertFalse(result)
        // no tmp files remain
        val tmpFiles = Files.list(tempDir).use { it.filter { p -> p.toString().endsWith(".tmp") }.count() }
        assertEquals(0, tmpFiles)
    }

    @Test
    fun `short-circuits -- does not download tokenizer if model fails`() {
        wireMock.stubFor(
            get(urlEqualTo(MODEL_PATH))
                .willReturn(aResponse().withStatus(500)),
        )

        val result = downloader().ensureModelFiles()

        assertFalse(result)
        wireMock.verify(1, getRequestedFor(urlEqualTo(MODEL_PATH)))
        wireMock.verify(0, getRequestedFor(urlEqualTo(TOKENIZER_PATH)))
    }

    @Test
    fun `handles 302 redirect`() {
        val redirectTarget = "/cdn/model.onnx"
        wireMock.stubFor(
            get(urlEqualTo(MODEL_PATH))
                .willReturn(
                    aResponse()
                        .withStatus(302)
                        .withHeader("Location", "http://localhost:${wireMock.port()}$redirectTarget"),
                ),
        )
        wireMock.stubFor(
            get(urlEqualTo(redirectTarget))
                .willReturn(aResponse().withStatus(200).withBody("redirected-model")),
        )
        wireMock.stubFor(
            get(urlEqualTo(TOKENIZER_PATH))
                .willReturn(aResponse().withStatus(200).withBody("tokenizer")),
        )

        val result = downloader().ensureModelFiles()

        assertTrue(result)
        assertEquals("redirected-model", Files.readString(tempDir.resolve("model.onnx")))
    }
}
