package io.github.klaw.engine.message

data class ScheduledMessage(
    val name: String,
    val message: String,
    val model: String?,
    val injectInto: String?,
    val channel: String? = null,
    val runId: String? = null,
    val sourceChatId: String? = null,
    val sourceChannel: String? = null,
)
