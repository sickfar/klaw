package io.github.klaw.engine.session

import kotlin.time.Instant

data class Session(
    val chatId: String,
    val model: String,
    val segmentStart: String,
    val createdAt: Instant,
)
