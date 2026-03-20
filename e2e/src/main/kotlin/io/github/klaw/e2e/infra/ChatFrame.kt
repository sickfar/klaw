package io.github.klaw.e2e.infra

import kotlinx.serialization.Serializable

/**
 * Local redeclaration of the ChatFrame protocol model.
 * Avoids pulling in the :common KMP module dependency.
 */
@Serializable
data class ChatFrame(
    val type: String,
    val content: String = "",
    val approvalId: String? = null,
    val riskScore: Int? = null,
    val timeout: Int? = null,
    val approved: Boolean? = null,
    val attachments: List<String>? = null,
)
