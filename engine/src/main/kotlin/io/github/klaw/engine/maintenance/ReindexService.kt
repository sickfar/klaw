package io.github.klaw.engine.maintenance

import io.github.klaw.common.conversation.ConversationMessage
import io.github.klaw.common.conversation.MessageMeta
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.db.KlawDatabase
import io.github.klaw.engine.util.VT
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

@Singleton
class ReindexService(
    private val database: KlawDatabase,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun reindex(
        conversationsDir: String = KlawPaths.conversations,
        onProgress: (String) -> Unit = {},
    ) {
        withContext(Dispatchers.VT) {
            clearMessages(onProgress)
            rebuildMessages(conversationsDir, onProgress)
            onProgress("Reindex complete")
        }
    }

    private fun clearMessages(onProgress: (String) -> Unit) {
        onProgress("Clearing existing messages...")
        database.messagesQueries.deleteAllMessages()
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
}
