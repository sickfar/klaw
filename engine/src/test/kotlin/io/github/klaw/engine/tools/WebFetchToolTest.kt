package io.github.klaw.engine.tools

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import io.github.klaw.common.config.WebFetchConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@Suppress("LargeClass")
class WebFetchToolTest {
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

    private fun tool(maxBytes: Long = 1_048_576): WebFetchTool {
        val config =
            WebFetchConfig(
                requestTimeoutMs = 5000,
                maxResponseSizeBytes = maxBytes,
                userAgent = "Test/1.0",
            )
        return WebFetchTool(config)
    }

    private fun baseUrl(): String = "http://localhost:${server.port()}"

    @Test
    fun `html fetch returns markdown output with metadata header`() =
        runBlocking {
            server.stubFor(
                get(urlEqualTo("/page"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html; charset=utf-8")
                            .withBody(
                                """
                                <html>
                                <head>
                                    <title>Test Page</title>
                                    <meta name="description" content="A test page description">
                                </head>
                                <body>
                                    <h1>Hello World</h1>
                                    <p>This is a test paragraph.</p>
                                </body>
                                </html>
                                """.trimIndent(),
                            ),
                    ),
            )

            val result = tool().fetch("${baseUrl()}/page")

            assertTrue(result.contains("URL: ${baseUrl()}/page"), "Should contain URL")
            assertTrue(result.contains("Status: 200"), "Should contain status")
            assertTrue(result.contains("Title: Test Page"), "Should contain title")
            assertTrue(result.contains("Description: A test page description"), "Should contain description")
            assertTrue(result.contains("Content-Length:"), "Should contain content length")
            assertTrue(result.contains("---"), "Should contain separator")
            assertTrue(result.contains("Hello World"), "Should contain markdown body")
            assertTrue(result.contains("This is a test paragraph"), "Should contain paragraph text")
        }

    @Test
    fun `plain text fetch returns raw text with metadata`() =
        runBlocking {
            server.stubFor(
                get(urlEqualTo("/text"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/plain")
                            .withBody("Hello, this is plain text content."),
                    ),
            )

            val result = tool().fetch("${baseUrl()}/text")

            assertTrue(result.contains("URL: ${baseUrl()}/text"))
            assertTrue(result.contains("Status: 200"))
            assertTrue(result.contains("Content-Type: text/plain"))
            assertTrue(result.contains("Content-Length:"))
            assertTrue(result.contains("---"))
            assertTrue(result.contains("Hello, this is plain text content."))
        }

    @Test
    fun `json fetch returns raw json with metadata`() =
        runBlocking {
            val jsonBody = """{"key": "value", "number": 42}"""
            server.stubFor(
                get(urlEqualTo("/api/data"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/json")
                            .withBody(jsonBody),
                    ),
            )

            val result = tool().fetch("${baseUrl()}/api/data")

            assertTrue(result.contains("URL: ${baseUrl()}/api/data"))
            assertTrue(result.contains("Status: 200"))
            assertTrue(result.contains("Content-Type: application/json"))
            assertTrue(result.contains("---"))
            assertTrue(result.contains(jsonBody))
        }

    @Test
    fun `binary content type returns error`() =
        runBlocking {
            server.stubFor(
                get(urlEqualTo("/file.bin"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/octet-stream")
                            .withBody(ByteArray(100)),
                    ),
            )

            val result = tool().fetch("${baseUrl()}/file.bin")

            assertTrue(result.contains("Error"), "Should contain error")
            assertTrue(result.contains("unsupported content type"), "Should mention unsupported content type")
        }

    @Test
    fun `non-http scheme returns error`() =
        runBlocking {
            val result = tool().fetch("ftp://example.com/file.txt")

            assertTrue(result.contains("Error"), "Should contain error")
            assertTrue(result.contains("http"), "Should mention supported schemes")
        }

    @Test
    fun `invalid URL returns error`() =
        runBlocking {
            val result = tool().fetch("not-a-url")

            assertTrue(result.contains("Error"), "Should contain error")
        }

    @Test
    fun `http 404 returns error with status code`() =
        runBlocking {
            server.stubFor(
                get(urlEqualTo("/missing"))
                    .willReturn(
                        aResponse()
                            .withStatus(404)
                            .withHeader("Content-Type", "text/html")
                            .withBody("Not Found"),
                    ),
            )

            val result = tool().fetch("${baseUrl()}/missing")

            assertTrue(result.contains("Error"), "Should contain error")
            assertTrue(result.contains("404"), "Should contain status code 404")
        }

    @Test
    fun `http 500 returns error with status code`() =
        runBlocking {
            server.stubFor(
                get(urlEqualTo("/error"))
                    .willReturn(
                        aResponse()
                            .withStatus(500)
                            .withHeader("Content-Type", "text/html")
                            .withBody("Internal Server Error"),
                    ),
            )

            val result = tool().fetch("${baseUrl()}/error")

            assertTrue(result.contains("Error"), "Should contain error")
            assertTrue(result.contains("500"), "Should contain status code 500")
        }

    @Test
    fun `content length exceeds max returns error without downloading body`() =
        runBlocking {
            server.stubFor(
                get(urlEqualTo("/huge"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withHeader("Content-Length", "999999999")
                            .withBody("small body"),
                    ),
            )

            val result = tool(maxBytes = 1024).fetch("${baseUrl()}/huge")

            assertTrue(result.contains("Error"), "Should contain error")
            assertTrue(result.contains("too large"), "Should mention size limit")
        }

    @Test
    fun `streaming cutoff when no content length and body exceeds max`() =
        runBlocking {
            val largeBody = "x".repeat(2048)
            server.stubFor(
                get(urlEqualTo("/stream"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/plain")
                            .withBody(largeBody),
                    ),
            )

            val result = tool(maxBytes = 1024).fetch("${baseUrl()}/stream")

            assertTrue(result.contains("truncated"), "Should mention truncation")
            assertTrue(result.contains("---"))
            // Body should be cut to maxBytes
            val bodyPart = result.substringAfter("---\n")
            assertTrue(bodyPart.length <= 1024 + 100, "Body should be roughly limited to max bytes")
        }

    @Test
    fun `redirect is followed`() =
        runBlocking {
            server.stubFor(
                get(urlEqualTo("/redirect"))
                    .willReturn(
                        aResponse()
                            .withStatus(302)
                            .withHeader("Location", "${baseUrl()}/target"),
                    ),
            )
            server.stubFor(
                get(urlEqualTo("/target"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/plain")
                            .withBody("Redirect target content"),
                    ),
            )

            val result = tool().fetch("${baseUrl()}/redirect")

            assertTrue(result.contains("Status: 200"), "Should show final status")
            assertTrue(result.contains("Redirect target content"), "Should contain target body")

            server.verify(getRequestedFor(urlEqualTo("/redirect")))
            server.verify(getRequestedFor(urlEqualTo("/target")))
        }

    @Test
    fun `empty response body is handled gracefully`() =
        runBlocking {
            server.stubFor(
                get(urlEqualTo("/empty"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/plain")
                            .withBody(""),
                    ),
            )

            val result = tool().fetch("${baseUrl()}/empty")

            assertTrue(result.contains("Status: 200"), "Should contain status")
            assertTrue(result.contains("---"), "Should contain separator")
            assertFalse(result.contains("Error"), "Should not be an error")
        }

    @Test
    fun `spa warning is included when detected`() =
        runBlocking {
            val spaHtml =
                """
                <html>
                <head><title>SPA App</title></head>
                <body>
                    <div id="root"></div>
                    <script src="app.js"></script>
                    <script src="vendor.js"></script>
                    <script src="runtime.js"></script>
                </body>
                </html>
                """.trimIndent()

            server.stubFor(
                get(urlEqualTo("/spa"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withBody(spaHtml),
                    ),
            )

            val result = tool().fetch("${baseUrl()}/spa")

            assertTrue(result.contains("Warning"), "Should contain SPA warning")
            assertTrue(result.contains("JavaScript"), "Warning should mention JavaScript")
        }

    @Test
    fun `user agent header is sent`() =
        runBlocking {
            server.stubFor(
                get(urlEqualTo("/ua"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/plain")
                            .withBody("ok"),
                    ),
            )

            tool().fetch("${baseUrl()}/ua")

            server.verify(
                getRequestedFor(urlEqualTo("/ua"))
                    .withHeader(
                        "User-Agent",
                        com.github.tomakehurst.wiremock.client.WireMock
                            .equalTo("Test/1.0"),
                    ),
            )
        }

    @Test
    fun `xml content type returns raw text`() =
        runBlocking {
            val xmlBody = """<?xml version="1.0"?><root><item>Hello</item></root>"""
            server.stubFor(
                get(urlEqualTo("/data.xml"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "application/xml")
                            .withBody(xmlBody),
                    ),
            )

            val result = tool().fetch("${baseUrl()}/data.xml")

            assertTrue(result.contains("Content-Type: application/xml"))
            assertTrue(result.contains(xmlBody))
        }

    @Test
    fun `url with no scheme returns error`() =
        runBlocking {
            val result = tool().fetch("example.com/page")

            assertTrue(result.contains("Error"), "Should contain error")
        }

    @Test
    fun `html without title or description omits those fields`() =
        runBlocking {
            server.stubFor(
                get(urlEqualTo("/bare"))
                    .willReturn(
                        aResponse()
                            .withStatus(200)
                            .withHeader("Content-Type", "text/html")
                            .withBody("<html><body><p>Just content</p></body></html>"),
                    ),
            )

            val result = tool().fetch("${baseUrl()}/bare")

            assertTrue(result.contains("Status: 200"))
            assertFalse(result.contains("Title:"), "Should not contain Title when missing")
            assertFalse(result.contains("Description:"), "Should not contain Description when missing")
            assertTrue(result.contains("Just content"))
        }
}
