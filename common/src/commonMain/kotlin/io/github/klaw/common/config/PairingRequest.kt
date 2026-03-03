package io.github.klaw.common.config

import kotlinx.serialization.Serializable

@Serializable
data class PairingRequest(
    val code: String,
    val channel: String,
    val chatId: String,
    val userId: String? = null,
    val createdAt: String,
)
