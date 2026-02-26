package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.AutoRagConfig
import io.github.klaw.common.util.approximateTokenCount
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.memory.EmbeddingService
import io.github.klaw.engine.util.VT
import io.github.klaw.engine.util.floatArrayToBlob
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val logger = KotlinLogging.logger {}

@Singleton
class MessageEmbeddingService(
    private val driver: JdbcSqliteDriver,
    private val embeddingService: EmbeddingService,
    private val sqliteVecLoader: SqliteVecLoader,
) {
    /**
     * Fire-and-forget: launches in [scope] and returns immediately.
     * Never blocks — caller delivers response to user before embed completes.
     */
    @Suppress("LongParameterList")
    fun embedAsync(
        messageRowId: Long,
        role: String,
        type: String,
        content: String,
        config: AutoRagConfig,
        scope: CoroutineScope,
    ) {
        if (!sqliteVecLoader.isAvailable()) return
        if (!isEligible(role, type, content, config)) return
        scope.launch {
            try {
                val embedding = embeddingService.embed(content)
                val blob = floatArrayToBlob(embedding)
                withContext(Dispatchers.VT) {
                    driver.execute(
                        null,
                        "INSERT OR IGNORE INTO vec_messages(rowid, embedding) VALUES (?, ?)",
                        2,
                    ) {
                        bindLong(0, messageRowId)
                        bindBytes(1, blob)
                    }
                }
                logger.trace { "Message embedding stored rowId=$messageRowId" }
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.warn { "Failed to embed message rowId=$messageRowId role=$role: ${e::class.simpleName}" }
            }
        }
    }

    /** Internal for unit testing. */
    @Suppress("ReturnCount")
    internal fun isEligible(
        role: String,
        type: String,
        content: String,
        config: AutoRagConfig,
    ): Boolean {
        if (role != "user" && role != "assistant") return false
        if (role == "assistant" && type == "tool_call") return false
        if (approximateTokenCount(content) < config.minMessageTokens) return false
        return true
    }
}
