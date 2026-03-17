package io.github.klaw.gateway.channel

import java.io.IOException

class PermanentDeliveryError(
    val reason: PermanentErrorReason,
    cause: Throwable? = null,
) : IOException("Permanent delivery failure: ${reason.name}", cause)

enum class PermanentErrorReason {
    BOT_BLOCKED,
    CHAT_NOT_FOUND,
    INSUFFICIENT_PERMISSIONS,
}

private val PERMANENT_PATTERNS: List<Pair<Regex, PermanentErrorReason>> =
    listOf(
        Regex("bot was blocked", RegexOption.IGNORE_CASE) to PermanentErrorReason.BOT_BLOCKED,
        Regex("chat not found", RegexOption.IGNORE_CASE) to PermanentErrorReason.CHAT_NOT_FOUND,
        Regex("user not found", RegexOption.IGNORE_CASE) to PermanentErrorReason.CHAT_NOT_FOUND,
        Regex("PEER_ID_INVALID", RegexOption.IGNORE_CASE) to PermanentErrorReason.CHAT_NOT_FOUND,
        Regex("not enough rights", RegexOption.IGNORE_CASE) to PermanentErrorReason.INSUFFICIENT_PERMISSIONS,
        Regex("bot is not a member", RegexOption.IGNORE_CASE) to PermanentErrorReason.INSUFFICIENT_PERMISSIONS,
    )

fun detectPermanentError(errorMessage: String): PermanentErrorReason? =
    PERMANENT_PATTERNS.firstOrNull { (pattern, _) -> pattern.containsMatchIn(errorMessage) }?.second

fun detectPermanentError(throwable: Throwable): PermanentErrorReason? {
    val message = throwable.message ?: return null
    detectPermanentError(message)?.let { return it }
    val causeMessage = throwable.cause?.message ?: return null
    return detectPermanentError(causeMessage)
}
