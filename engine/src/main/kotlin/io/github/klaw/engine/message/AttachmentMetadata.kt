package io.github.klaw.engine.message

import kotlinx.serialization.Serializable

@Serializable
data class AttachmentMetadata(
    val attachments: List<AttachmentRef>,
    val descriptions: Map<String, String> = emptyMap(),
)

@Serializable
data class AttachmentRef(
    val path: String,
    val mimeType: String,
)
