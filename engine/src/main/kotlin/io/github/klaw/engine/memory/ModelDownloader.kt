package io.github.klaw.engine.memory

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration

private val logger = KotlinLogging.logger {}

class ModelDownloader(
    private val modelDir: Path,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(CONNECT_TIMEOUT)
            .build(),
) {
    fun ensureModelFiles(): Boolean {
        val modelOk =
            downloadFile(
                "$baseUrl$ONNX_MODEL_PATH",
                modelDir.resolve(MODEL_FILENAME),
            )
        if (!modelOk) return false
        return downloadFile(
            "$baseUrl$TOKENIZER_PATH",
            modelDir.resolve(TOKENIZER_FILENAME),
        )
    }

    private fun downloadFile(
        url: String,
        targetFile: Path,
    ): Boolean {
        logger.trace { "Checking for ${targetFile.fileName} at $targetFile" }
        if (Files.exists(targetFile)) {
            logger.trace { "${targetFile.fileName} already present" }
            return true
        }

        return try {
            Files.createDirectories(targetFile.parent)
            val tmpFile = targetFile.resolveSibling("${targetFile.fileName}.tmp")

            logger.info { "Downloading ONNX model file: ${targetFile.fileName}" }
            val response = sendGetRequest(url)
            if (response.statusCode() != HTTP_OK) {
                logger.warn { "Failed to download ${targetFile.fileName}: HTTP ${response.statusCode()}" }
                return false
            }

            streamToFile(response, tmpFile, targetFile)
        } catch (e: IOException) {
            logger.warn { "Failed to download ${targetFile.fileName}: ${e::class.simpleName}" }
            false
        }
    }

    private fun sendGetRequest(url: String): HttpResponse<java.io.InputStream> {
        val request =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .timeout(READ_TIMEOUT)
                .GET()
                .build()
        return httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
    }

    private fun streamToFile(
        response: HttpResponse<java.io.InputStream>,
        tmpFile: Path,
        targetFile: Path,
    ): Boolean =
        try {
            response.body().use { input ->
                Files.newOutputStream(tmpFile).use { output ->
                    input.copyTo(output, BUFFER_SIZE)
                }
            }
            Files.move(tmpFile, targetFile, StandardCopyOption.ATOMIC_MOVE)
            val bytes = Files.size(targetFile)
            logger.info { "Downloaded ${targetFile.fileName} ($bytes bytes)" }
            true
        } finally {
            Files.deleteIfExists(tmpFile)
        }

    companion object {
        private const val MODEL_FILENAME = "model.onnx"
        private const val TOKENIZER_FILENAME = "tokenizer.json"
        private const val DEFAULT_BASE_URL = "https://huggingface.co"
        private const val ONNX_MODEL_PATH =
            "/intfloat/multilingual-e5-small/resolve/main/onnx/model.onnx"
        private const val TOKENIZER_PATH =
            "/intfloat/multilingual-e5-small/resolve/main/tokenizer.json"
        private const val HTTP_OK = 200
        private const val BUFFER_SIZE = 8192
        private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(30)
        private val READ_TIMEOUT: Duration = Duration.ofMinutes(5)
    }
}
