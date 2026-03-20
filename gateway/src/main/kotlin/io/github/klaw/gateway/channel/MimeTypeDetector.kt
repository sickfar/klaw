package io.github.klaw.gateway.channel

private val EXTENSION_TO_MIME =
    mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "svg" to "image/svg+xml",
        "tiff" to "image/tiff",
        "tif" to "image/tiff",
        "ico" to "image/x-icon",
        "pdf" to "application/pdf",
    )

private const val DEFAULT_MIME_TYPE = "application/octet-stream"

internal fun detectMimeType(path: String): String {
    val ext = path.substringAfterLast('.', "").lowercase()
    return EXTENSION_TO_MIME[ext] ?: DEFAULT_MIME_TYPE
}
