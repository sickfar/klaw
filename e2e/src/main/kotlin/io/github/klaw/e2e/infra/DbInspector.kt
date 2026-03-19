package io.github.klaw.e2e.infra

import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

private val logger = KotlinLogging.logger {}

private const val BUSY_TIMEOUT_MS = 5000
private const val MAX_RETRIES = 5
private const val RETRY_DELAY_MS = 1000L

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
        logger.debug { "Opening DB (read-only): ${dbFile.absolutePath}" }
        connection = openWithRetry(dbFile)
    }

    private fun openWithRetry(dbFile: File): Connection {
        var lastException: SQLException? = null
        repeat(MAX_RETRIES) { attempt ->
            try {
                val conn =
                    DriverManager.getConnection(
                        "jdbc:sqlite:file:${dbFile.absolutePath}?mode=ro",
                    )
                conn.createStatement().use {
                    // Don't set journal_mode — it's a write operation and the engine owns the DB.
                    // Read-only mode avoids WAL conflicts across Docker bind mounts.
                    it.execute("PRAGMA busy_timeout=$BUSY_TIMEOUT_MS")
                }
                return conn
            } catch (e: SQLException) {
                lastException = e
                logger.debug {
                    "DB open failed (attempt ${attempt + 1}/$MAX_RETRIES): ${e::class.simpleName}"
                }
                if (attempt < MAX_RETRIES - 1) {
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw lastException!!
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

    fun getSubagentRuns(): List<SubagentRunRow> =
        withRetry("getSubagentRuns") {
            val results = mutableListOf<SubagentRunRow>()
            connection
                .prepareStatement(
                    """
                    SELECT id, name, status, start_time, end_time, duration_ms,
                           result, last_response, error
                    FROM subagent_runs ORDER BY start_time DESC
                    """.trimIndent(),
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(
                                SubagentRunRow(
                                    id = rs.getString("id"),
                                    name = rs.getString("name"),
                                    status = rs.getString("status"),
                                    startTime = rs.getString("start_time"),
                                    endTime = rs.getString("end_time"),
                                    durationMs = rs.getLong("duration_ms").takeIf { !rs.wasNull() },
                                    result = rs.getString("result"),
                                    lastResponse = rs.getString("last_response"),
                                    error = rs.getString("error"),
                                ),
                            )
                        }
                    }
                }
            results
        }

    fun getSubagentRunByName(name: String): SubagentRunRow? =
        withRetry("getSubagentRunByName") {
            connection
                .prepareStatement(
                    """
                    SELECT id, name, status, start_time, end_time, duration_ms,
                           result, last_response, error
                    FROM subagent_runs WHERE name = ? ORDER BY start_time DESC LIMIT 1
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, name)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) {
                            SubagentRunRow(
                                id = rs.getString("id"),
                                name = rs.getString("name"),
                                status = rs.getString("status"),
                                startTime = rs.getString("start_time"),
                                endTime = rs.getString("end_time"),
                                durationMs = rs.getLong("duration_ms").takeIf { !rs.wasNull() },
                                result = rs.getString("result"),
                                lastResponse = rs.getString("last_response"),
                                error = rs.getString("error"),
                            )
                        } else {
                            null
                        }
                    }
                }
        }

    fun getMemoryCategories(): List<MemoryCategoryRow> =
        withRetry("getMemoryCategories") {
            val results = mutableListOf<MemoryCategoryRow>()
            connection
                .prepareStatement(
                    "SELECT id, name, access_count, created_at FROM memory_categories ORDER BY access_count DESC",
                ).use { ps ->
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(
                                MemoryCategoryRow(
                                    id = rs.getLong("id"),
                                    name = rs.getString("name"),
                                    accessCount = rs.getLong("access_count"),
                                    createdAt = rs.getString("created_at"),
                                ),
                            )
                        }
                    }
                }
            results
        }

    fun getMemoryCategoryCount(): Int =
        withRetry("getMemoryCategoryCount") {
            connection
                .prepareStatement("SELECT COUNT(*) FROM memory_categories")
                .use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    fun getMemoryFactCount(): Int =
        withRetry("getMemoryFactCount") {
            connection
                .prepareStatement("SELECT COUNT(*) FROM memory_facts")
                .use { ps ->
                    ps.executeQuery().use { rs ->
                        rs.next()
                        rs.getInt(1)
                    }
                }
        }

    fun getMemoryFactsBySourcePrefix(prefix: String): List<MemoryFactRow> =
        withRetry("getMemoryFactsBySourcePrefix") {
            val results = mutableListOf<MemoryFactRow>()
            connection
                .prepareStatement(
                    """
                    SELECT mf.id, mf.source, mf.content, mf.created_at
                    FROM memory_facts mf
                    WHERE mf.source LIKE ?
                    ORDER BY mf.id
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, "$prefix%")
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            results.add(
                                MemoryFactRow(
                                    id = rs.getLong("id"),
                                    source = rs.getString("source"),
                                    content = rs.getString("content"),
                                    createdAt = rs.getString("created_at"),
                                ),
                            )
                        }
                    }
                }
            results
        }

    fun getMemoryFactCountByCategory(categoryName: String): Int =
        withRetry("getMemoryFactCountByCategory") {
            connection
                .prepareStatement(
                    """
                    SELECT COUNT(*) FROM memory_facts mf
                    JOIN memory_categories mc ON mc.id = mf.category_id
                    WHERE mc.name = ? COLLATE NOCASE
                    """.trimIndent(),
                ).use { ps ->
                    ps.setString(1, categoryName)
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

data class MemoryCategoryRow(
    val id: Long,
    val name: String,
    val accessCount: Long,
    val createdAt: String,
)

data class MemoryFactRow(
    val id: Long,
    val source: String,
    val content: String,
    val createdAt: String,
)

data class SubagentRunRow(
    val id: String,
    val name: String,
    val status: String,
    val startTime: String,
    val endTime: String?,
    val durationMs: Long?,
    val result: String?,
    val lastResponse: String?,
    val error: String?,
)
