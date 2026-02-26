package io.github.klaw.engine.maintenance

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.conversation.ConversationMessage
import io.github.klaw.common.conversation.MessageMeta
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.util.approximateTokenCount
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.db.SqliteVecLoader
import io.github.klaw.engine.memory.EmbeddingService
import io.github.klaw.engine.util.VT
import io.github.klaw.engine.util.floatArrayToBlob
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

private val logger = KotlinLogging.logger {}

@Singleton
class ReindexService(
    private val database: KlawDatabase,
    private val driver: JdbcSqliteDriver,
    private val embeddingService: EmbeddingService,
    private val sqliteVecLoader: SqliteVecLoader,
    private val config: EngineConfig,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun reindex(
        conversationsDir: String = KlawPaths.conversations,
        onProgress: (String) -> Unit = {},
    ) {
        withContext(Dispatchers.VT) {
            clearMessages(onProgress)
            rebuildMessages(conversationsDir, onProgress)
        }
        rebuildVecMessages(onProgress)
        onProgress("Reindex complete")
    }

    private fun clearMessages(onProgress: (String) -> Unit) {
        onProgress("Clearing existing messages...")
        database.messagesQueries.deleteAllMessages()
        if (sqliteVecLoader.isAvailable()) {
            onProgress("Clearing vec_messages...")
            driver.execute(null, "DELETE FROM vec_messages", 0)
        }
    }

    private fun rebuildMessages(
        conversationsDir: String,
        onProgress: (String) -> Unit,
    ) {
        val dir = File(conversationsDir)
        if (!dir.exists()) {
            onProgress("No conversations directory found")
            return
        }

        val chatDirs = dir.listFiles { f -> f.isDirectory } ?: return
        onProgress("Found ${chatDirs.size} conversation(s)")

        for (chatDir in chatDirs) {
            val chatId = chatDir.name
            val jsonlFile = File(chatDir, "$chatId.jsonl")
            if (!jsonlFile.exists()) continue

            onProgress("Processing $chatId...")

            database.messagesQueries.transaction {
                jsonlFile.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        try {
                            val msg = json.decodeFromString<ConversationMessage>(line)
                            database.messagesQueries.insertMessage(
                                id = msg.id,
                                channel = msg.meta?.channel ?: "unknown",
                                chat_id = chatId,
                                role = msg.role,
                                type = msg.type ?: "text",
                                content = msg.content,
                                metadata =
                                    msg.meta?.let {
                                        Json.encodeToString(MessageMeta.serializer(), it)
                                    },
                                created_at = msg.ts,
                            )
                        } catch (_: Exception) {
                            // Skip malformed lines gracefully
                        }
                    }
                }
            }
        }
    }

    private suspend fun rebuildVecMessages(onProgress: (String) -> Unit) {
        if (!sqliteVecLoader.isAvailable()) return
        onProgress("Rebuilding vec_messages embeddings...")

        // Get all messages in VT context (JDBC blocking)
        val rows =
            withContext(Dispatchers.VT) {
                database.messagesQueries.getAllMessages().executeAsList()
            }

        var embedded = 0
        @Suppress("LoopWithTooManyJumpStatements")
        for (row in rows) {
            val role = row.role
            val type = row.type
            val content = row.content

            // Eligibility check: user or assistant, not tool_call, sufficient tokens
            if (role != "user" && role != "assistant") continue
            if (role == "assistant" && type == "tool_call") continue
            if (approximateTokenCount(content) < config.autoRag.minMessageTokens) continue

            try {
                val embedding = embeddingService.embed(content)
                val blob = floatArrayToBlob(embedding)
                withContext(Dispatchers.VT) {
                    driver.execute(
                        null,
                        "INSERT OR IGNORE INTO vec_messages(rowid, embedding) VALUES (?, ?)",
                        2,
                    ) {
                        bindLong(0, row.rowid)
                        bindBytes(1, blob)
                    }
                }
                embedded++
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.warn { "Failed to embed message rowid=${row.rowid}: ${e::class.simpleName}" }
            }
        }
        logger.debug { "vec_messages rebuild: embedded=$embedded" }
        onProgress("Embedded $embedded message(s) into vec_messages")
    }
}
