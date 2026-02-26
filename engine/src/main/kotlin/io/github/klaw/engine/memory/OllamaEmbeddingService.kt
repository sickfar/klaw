package io.github.klaw.engine.memory

import io.github.klaw.engine.util.VT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Suppress("MagicNumber")
class OllamaEmbeddingService(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "all-minilm:l6-v2",
) : EmbeddingService {
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    @Serializable
    private data class SingleEmbedRequest(
        val model: String,
        val input: String,
    )

    @Serializable
    private data class BatchEmbedRequest(
        val model: String,
        val input: List<String>,
    )

    @Serializable
    private data class EmbedResponse(
        val embeddings: List<List<Float>>,
        @SerialName("model") val responseModel: String? = null,
    )

    override suspend fun embed(text: String): FloatArray =
        withContext(Dispatchers.VT) {
            val requestBody = json.encodeToString(SingleEmbedRequest(model, text))
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("$baseUrl/api/embed"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                error("Ollama embed failed: HTTP ${response.statusCode()} — ${response.body()}")
            }

            val parsed = json.decodeFromString<EmbedResponse>(response.body())
            val embedding =
                parsed.embeddings.firstOrNull()
                    ?: error("Ollama returned empty embeddings for model $model")
            embedding.toFloatArray()
        }

    override suspend fun embedBatch(texts: List<String>): List<FloatArray> =
        withContext(Dispatchers.VT) {
            val requestBody = json.encodeToString(BatchEmbedRequest(model, texts))
            val request =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create("$baseUrl/api/embed"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build()

            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() != 200) {
                error("Ollama embed failed: HTTP ${response.statusCode()} — ${response.body()}")
            }

            val parsed = json.decodeFromString<EmbedResponse>(response.body())
            parsed.embeddings.map { it.toFloatArray() }
        }
}
