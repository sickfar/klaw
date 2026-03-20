package io.github.klaw.gateway.pairing

import io.github.klaw.common.config.PairingRequest
import io.github.klaw.common.paths.KlawPathsSnapshot
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

sealed class PairingCodeResult {
    data class Success(
        val code: String,
    ) : PairingCodeResult()

    data object RateLimited : PairingCodeResult()

    data object AlreadyPaired : PairingCodeResult()
}

@Singleton
class PairingService(
    private val paths: KlawPathsSnapshot,
) {
    private val lock = ReentrantLock()
    private val requests = mutableListOf<PairingRequest>()
    private val lastRequestTime = ConcurrentHashMap<String, Instant>()

    private val pairingJson =
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

    companion object {
        private const val CODE_LENGTH = 6
        private const val CODE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        private const val RATE_LIMIT_SECONDS = 60L
        private const val EXPIRY_MINUTES = 5L
    }

    init {
        val loaded = loadRequests()
        requests.addAll(loaded)
        cleanExpired()
        logger.debug { "PairingService initialized with ${requests.size} pending requests" }
    }

    fun generateCode(
        channel: String,
        chatId: String,
        userId: String?,
        guildId: String? = null,
    ): PairingCodeResult {
        val now = Instant.now()
        val key = "$channel:$chatId"
        val lastTime = lastRequestTime[key]
        if (lastTime != null && ChronoUnit.SECONDS.between(lastTime, now) < RATE_LIMIT_SECONDS) {
            logger.trace { "Rate limited pairing request for chatId=$chatId" }
            return PairingCodeResult.RateLimited
        }

        val code = generateRandomCode()
        val request =
            PairingRequest(
                code = code,
                channel = channel,
                chatId = chatId,
                userId = userId,
                guildId = guildId,
                createdAt = now.toString(),
            )
        lock.withLock {
            requests.removeAll { it.channel == channel && it.chatId == chatId && it.userId == userId }
            requests.add(request)
            lastRequestTime[key] = now
            saveRequests(requests)
        }
        logger.debug { "Generated pairing code for chatId=$chatId" }
        return PairingCodeResult.Success(code)
    }

    fun loadRequests(): List<PairingRequest> {
        val file = File(paths.pairingRequests)
        if (!file.exists()) return emptyList()
        return try {
            pairingJson.decodeFromString<List<PairingRequest>>(file.readText())
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            logger.warn(e) { "Failed to load pairing requests" }
            emptyList()
        }
    }

    fun saveRequests(reqs: List<PairingRequest>) {
        val file = File(paths.pairingRequests)
        file.parentFile?.mkdirs()
        file.writeText(pairingJson.encodeToString(reqs))
        logger.trace { "Saved ${reqs.size} pairing requests" }
    }

    fun cleanExpired() {
        lock.withLock {
            val cutoff = Instant.now().minus(EXPIRY_MINUTES, ChronoUnit.MINUTES)
            val before = requests.size
            requests.removeAll {
                try {
                    Instant.parse(it.createdAt).isBefore(cutoff)
                } catch (_: Exception) {
                    true
                }
            }
            if (before != requests.size) {
                saveRequests(requests)
                logger.debug { "Cleaned ${before - requests.size} expired pairing requests" }
            }
        }
    }

    fun hasPendingRequests(): Boolean = lock.withLock { requests.isNotEmpty() }

    private fun generateRandomCode(): String = (1..CODE_LENGTH).map { CODE_CHARS.random() }.joinToString("")
}
