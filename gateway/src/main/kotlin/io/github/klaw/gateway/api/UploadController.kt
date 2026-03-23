package io.github.klaw.gateway.api

import io.github.klaw.gateway.channel.UploadStore
import io.github.klaw.gateway.config.WsEnabledCondition
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpRequest
import io.micronaut.http.HttpResponse
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Post
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

@Controller("/upload")
@Requires(condition = WsEnabledCondition::class)
class UploadController(
    private val uploadStore: UploadStore,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @Post(consumes = [MediaType.ALL])
    fun upload(request: HttpRequest<ByteArray>): HttpResponse<String> {
        val contentType = request.contentType.map { it.toString() }.orElse("")
        if (!contentType.startsWith("image/")) {
            return errorResponse("""{"error":"unsupported content type, expected image/*"}""")
        }

        val filename =
            request.headers["X-Filename"]
                ?: "upload.${contentType.substringAfter('/')}"
        val bytes = request.body.orElse(null) ?: byteArrayOf()

        if (bytes.isEmpty()) {
            return errorResponse("""{"error":"empty body"}""")
        }

        val uploaded = uploadStore.save(bytes, filename, contentType)
        logger.debug { "Upload accepted: id=${uploaded.id}, size=${bytes.size}" }

        return HttpResponse
            .ok(json.encodeToString(UploadResponse(uploaded.id)))
            .contentType(MediaType.APPLICATION_JSON_TYPE)
    }

    private fun errorResponse(body: String): HttpResponse<String> =
        HttpResponse
            .badRequest<String>(body)
            .contentType(MediaType.APPLICATION_JSON_TYPE)

    @Serializable
    private data class UploadResponse(
        val id: String,
    )
}
