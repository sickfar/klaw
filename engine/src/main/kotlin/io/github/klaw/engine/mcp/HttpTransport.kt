package io.github.klaw.engine.mcp

import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

private val logger = KotlinLogging.logger {}

class HttpTransportException(
    val statusCode: Int,
) : RuntimeException("HTTP transport error: status $statusCode")

class HttpTransport(
    private val url: String,
    private val apiKey: String? = null,
) : McpTransport {
    private val httpClient: HttpClient = HttpClient.newHttpClient()
    private var pendingMessage: String? = null

    override suspend fun send(message: String) {
        pendingMessage = message
        logger.trace { "MCP HTTP message queued (${message.length} bytes)" }
    }

    override suspend fun receive(): String {
        val body = pendingMessage ?: error("No message to send; call send() before receive()")
        pendingMessage = null

        val requestBuilder =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))

        if (apiKey != null) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        val request = requestBuilder.build()

        val response =
            withContext(Dispatchers.VT) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }

        logger.debug { "MCP HTTP response: status=${response.statusCode()}, ${response.body().length} bytes" }

        if (response.statusCode() !in HTTP_OK_RANGE) {
            throw HttpTransportException(response.statusCode())
        }

        return response.body()
    }

    override suspend fun close() {
        // HTTP is stateless; nothing to close
    }

    override val isOpen: Boolean
        get() = true

    companion object {
        private val HTTP_OK_RANGE = 200..299
    }
}
