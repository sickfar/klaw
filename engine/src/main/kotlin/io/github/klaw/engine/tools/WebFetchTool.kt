package io.github.klaw.engine.tools

import io.github.klaw.common.config.WebFetchConfig
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

private const val MAX_TIMEOUT_SECONDS = 120
private val ALLOWED_SCHEMES = setOf("http", "https")
private val HTML_MIME_TYPES = setOf("text/html", "application/xhtml+xml")
private val TEXT_MIME_TYPES = setOf("application/json", "application/xml")

class WebFetchTool(
    private val config: WebFetchConfig,
) {
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofMillis(config.requestTimeoutMs))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    suspend fun fetch(
        url: String,
        timeoutSeconds: Int? = null,
    ): String {
        val uri = parseAndValidateUrl(url) ?: return formatUrlError(url)

        val timeout = computeTimeout(timeoutSeconds)
        val request = buildRequest(uri, timeout)

        val response = executeRequest(request, url) ?: return "Error: request failed"

        return processResponse(url, response)
    }

    private fun parseAndValidateUrl(url: String): URI? {
        val uri =
            try {
                URI.create(url)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.debug(e) { "web_fetch: invalid URL, length=${url.length}" }
                return null
            }
        if (uri.scheme == null || uri.scheme !in ALLOWED_SCHEMES) {
            return null
        }
        return uri
    }

    private fun formatUrlError(url: String): String {
        val uri =
            try {
                URI.create(url)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.debug(e) { "web_fetch: malformed URL, length=${url.length}" }
                return "Error: invalid URL"
            }
        return if (uri.scheme == null) {
            "Error: invalid URL — missing scheme"
        } else {
            "Error: only http and https URLs are supported, got '${uri.scheme}'"
        }
    }

    private fun computeTimeout(timeoutSeconds: Int?): Int =
        (timeoutSeconds ?: (config.requestTimeoutMs / MILLIS_PER_SECOND).toInt())
            .coerceIn(1, MAX_TIMEOUT_SECONDS)

    private fun buildRequest(
        uri: URI,
        timeoutSeconds: Int,
    ): HttpRequest =
        HttpRequest
            .newBuilder()
            .uri(uri)
            .header("User-Agent", config.userAgent)
            .timeout(Duration.ofSeconds(timeoutSeconds.toLong()))
            .GET()
            .build()

    private suspend fun executeRequest(
        request: HttpRequest,
        url: String,
    ): HttpResponse<java.io.InputStream>? =
        try {
            withContext(Dispatchers.VT) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn(e) { "web_fetch request failed for url length=${url.length}" }
            null
        }

    private suspend fun processResponse(
        url: String,
        response: HttpResponse<java.io.InputStream>,
    ): String {
        val statusCode = response.statusCode()
        val contentType = response.headers().firstValue("Content-Type").orElse("")
        val contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1)

        logger.trace { "web_fetch: status=$statusCode contentType=$contentType contentLength=$contentLength" }

        if (contentLength > config.maxResponseSizeBytes) {
            response.body().close()
            return "Error: response too large ($contentLength bytes, max ${config.maxResponseSizeBytes})"
        }

        if (statusCode !in HTTP_OK_RANGE) {
            response.body().close()
            return "Error: HTTP $statusCode"
        }

        val bodyResult = readBody(response)
        val bodyText = bodyResult.first
        val truncated = bodyResult.second

        val mimeType = contentType.substringBefore(";").trim().lowercase()

        return when {
            mimeType in HTML_MIME_TYPES -> {
                formatHtmlResponse(url, statusCode, bodyText, truncated)
            }

            mimeType.startsWith("text/") || mimeType in TEXT_MIME_TYPES -> {
                formatTextResponse(url, statusCode, mimeType, bodyText, truncated)
            }

            else -> {
                "Error: unsupported content type '$mimeType'"
            }
        }
    }

    private suspend fun readBody(response: HttpResponse<java.io.InputStream>): Pair<String, Boolean> {
        val maxBytes = config.maxResponseSizeBytes.toInt()
        val bodyBytes =
            try {
                withContext(Dispatchers.VT) {
                    response.body().use { stream ->
                        stream.readNBytes(maxBytes + 1)
                    }
                }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.warn(e) { "web_fetch: failed to read response body" }
                return "" to false
            }

        val truncated = bodyBytes.size > maxBytes
        val effectiveBytes = if (truncated) bodyBytes.copyOf(maxBytes) else bodyBytes
        return String(effectiveBytes, Charsets.UTF_8) to truncated
    }

    private fun formatHtmlResponse(
        url: String,
        status: Int,
        html: String,
        truncated: Boolean,
    ): String {
        val result = HtmlToMarkdown.convert(html)
        return buildString {
            append("URL: ").append(url).append('\n')
            append("Status: ").append(status).append('\n')
            if (result.title.isNotBlank()) {
                append("Title: ").append(result.title).append('\n')
            }
            if (result.description.isNotBlank()) {
                append("Description: ").append(result.description).append('\n')
            }
            append("Content-Length: ").append(html.length).append('\n')
            if (truncated) {
                append("Note: Response was truncated (exceeded size limit)\n")
            }
            if (result.spaWarning) {
                append("Warning: This page appears to use JavaScript rendering. Content may be incomplete.\n")
            }
            append("---\n")
            append(result.body)
        }
    }

    private fun formatTextResponse(
        url: String,
        status: Int,
        mimeType: String,
        body: String,
        truncated: Boolean,
    ): String =
        buildString {
            append("URL: ").append(url).append('\n')
            append("Status: ").append(status).append('\n')
            append("Content-Type: ").append(mimeType).append('\n')
            append("Content-Length: ").append(body.length).append('\n')
            if (truncated) {
                append("Note: Response was truncated (exceeded size limit)\n")
            }
            append("---\n")
            append(body)
        }

    companion object {
        private const val MILLIS_PER_SECOND = 1000L
        private val HTTP_OK_RANGE = 200..299
    }
}
