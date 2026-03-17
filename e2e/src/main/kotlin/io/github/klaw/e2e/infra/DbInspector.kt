package io.github.klaw.e2e.infra

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

private val logger = KotlinLogging.logger {}

private const val BUSY_TIMEOUT_MS = 5000
private const val MAX_RETRIES = 3
private const val RETRY_DELAY_MS = 500L

data class MessageRow(
    val id: String,
    val chatId: String,
    val role: String,
    val type: String,
    val content: String,
    val createdAt: String,
    val tokens: Int,
)

data class SummaryRow(
    val id: Int,
    val chatId: String,
    val fromMessageId: String,
    val toMessageId: String,
    val fromCreatedAt: String,
    val toCreatedAt: String,
    val filePath: String,
    val createdAt: String,
)

class DbInspector(
    dbFile: File,
) : AutoCloseable {
    private val connection: Connection

    init {
        logger.debug { "Opening DB: ${dbFile.absolutePath}" }
        connection = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}")
        connection.createStatement().use {
            it.execute("PRAGMA journal_mode=WAL")
            it.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
        }
    }

    private fun <T> withRetry(
        operation: String,
        block: () -> T,
    ): T {
        var lastException: SQLException? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                return block()
            } catch (e: SQLException) {
                lastException = e
                logger.debug {
                    "DB query '$operation' failed (attempt ${attempt + 1}/$MAX_RETRIES): ${e::class.simpleName}"
                }
                if (attempt < MAX_RETRIES - 1) {
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw lastException!!
    }

    fun getMessages(chatId: String): List<MessageRow> =
        withRetry("getMessages") {
            val results = mutableListOf<MessageRow>()
            connection
                .prepareStatement(
                    """
                    SELECT id, chat_id, role, type, content, created_at, tokens
                    FROM messages WHERE chat_id = ? ORDER BY created_at ASC
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, chatId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(
                                MessageRow(
                                    id = rs.getString("id"),
                                    chatId = rs.getString("chat_id"),
                                    role = rs.getString("role"),
                                    type = rs.getString("type"),
                                    content = rs.getString("content"),
                                    createdAt = rs.getString("created_at"),
                                    tokens = rs.getInt("tokens"),
                                ),
                            )
                        }
                    }
                }
            results
        }

    fun getMessageCount(chatId: String): Int =
        withRetry("getMessageCount") {
            connection
                .prepareStatement(
                    "SELECT COUNT(*) FROM messages WHERE chat_id = ?",
                ).use { ps ->
                    ps.setString(1, chatId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    fun getSummaries(chatId: String): List<SummaryRow> =
        withRetry("getSummaries") {
            val results = mutableListOf<SummaryRow>()
            connection
                .prepareStatement(
                    """
                    SELECT id, chat_id, from_message_id, to_message_id,
                           from_created_at, to_created_at, file_path, created_at
                    FROM summaries WHERE chat_id = ? ORDER BY created_at
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, chatId)
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(
                                SummaryRow(
                                    id = rs.getInt("id"),
                                    chatId = rs.getString("chat_id"),
                                    fromMessageId = rs.getString("from_message_id"),
                                    toMessageId = rs.getString("to_message_id"),
                                    fromCreatedAt = rs.getString("from_created_at"),
                                    toCreatedAt = rs.getString("to_created_at"),
                                    filePath = rs.getString("file_path"),
                                    createdAt = rs.getString("created_at"),
                                ),
                            )
                        }
                    }
                }
            results
        }

    fun getSummaryCount(chatId: String): Int =
        withRetry("getSummaryCount") {
            connection
                .prepareStatement(
                    "SELECT COUNT(*) FROM summaries WHERE chat_id = ?",
                ).use { ps ->
                    ps.setString(1, chatId)
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    override fun close() {
        connection.close()
        logger.debug { "DB inspector closed" }
    }
}
