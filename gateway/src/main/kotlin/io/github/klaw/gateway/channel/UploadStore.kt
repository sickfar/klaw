package io.github.klaw.gateway.channel

import io.github.klaw.common.config.GatewayConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

data class UploadedFile(
    val id: String,
    val path: Path,
    val mimeType: String,
    val originalName: String,
)

/**
 * In-memory store for uploaded files. Files are saved to the configured attachments
 * directory (shared with engine) or a temp directory if not configured.
 * Mapping is lost on restart — this is intentional (ephemeral uploads).
 */
@Singleton
class UploadStore(
    config: GatewayConfig,
) {
    private val uploads = ConcurrentHashMap<String, UploadedFile>()
    private val uploadDir: Path

    init {
        val configDir = config.attachments.directory
        uploadDir =
            if (configDir.isNotBlank()) {
                val dir = Path.of(configDir).resolve("local_ws_uploads")
                Files.createDirectories(dir)
                dir
            } else {
                Files.createTempDirectory("klaw-uploads")
            }
        logger.debug { "Upload directory: $uploadDir" }
    }

    fun save(
        bytes: ByteArray,
        originalName: String,
        mimeType: String,
    ): UploadedFile {
        val id = UUID.randomUUID().toString()
        val extension = originalName.substringAfterLast('.', "").lowercase()
        val fileName = if (extension.isNotEmpty()) "$id.$extension" else id
        val filePath = uploadDir.resolve(fileName)
        Files.write(filePath, bytes)

        val uploaded = UploadedFile(id = id, path = filePath, mimeType = mimeType, originalName = originalName)
        uploads[id] = uploaded
        logger.trace { "File uploaded: id=$id, size=${bytes.size}, mimeType=$mimeType" }
        return uploaded
    }

    fun resolve(id: String): UploadedFile? = uploads[id]

    fun resolveAll(ids: List<String>): List<UploadedFile> = ids.mapNotNull { uploads[it] }
}
