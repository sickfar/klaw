package io.github.klaw.engine.tools

import io.github.klaw.common.config.WebSearchConfig
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

private val logger = KotlinLogging.logger {}

class BraveSearchProvider(
    private val config: WebSearchConfig,
) : WebSearchProvider {
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofMillis(config.requestTimeoutMs))
            .build()

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(
        query: String,
        maxResults: Int,
    ): List<SearchResult> {
        val apiKey = config.apiKey ?: error("Brave Search API key is not configured")
        val encoded = URLEncoder.encode(query, Charsets.UTF_8)
        val url = "${config.braveEndpoint}/res/v1/web/search?q=$encoded&count=$maxResults"

        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .header("X-Subscription-Token", apiKey)
                .header("Accept", "application/json")
                .timeout(Duration.ofMillis(config.requestTimeoutMs))
                .GET()
                .build()

        val response =
            withContext(Dispatchers.VT) {
                httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            }

        logger.trace { "brave_search: status=${response.statusCode()} bodyBytes=${response.body().length}" }

        if (response.statusCode() !in HTTP_OK_RANGE) {
            error("Brave Search API error: HTTP ${response.statusCode()}")
        }

        return parseBraveResponse(response.body())
    }

    private fun parseBraveResponse(body: String): List<SearchResult> {
        val root = json.parseToJsonElement(body).jsonObject
        val results = root["web"]?.jsonObject?.get("results")?.jsonArray ?: return emptyList()
        return results.map { element ->
            val obj = element.jsonObject
            SearchResult(
                title = obj["title"]?.jsonPrimitive?.content.orEmpty(),
                url = obj["url"]?.jsonPrimitive?.content.orEmpty(),
                snippet = obj["description"]?.jsonPrimitive?.content.orEmpty(),
            )
        }
    }

    companion object {
        private val HTTP_OK_RANGE = 200..299
    }
}
