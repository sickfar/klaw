package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.isDirectory
import io.github.klaw.cli.util.listDirectory
import io.github.klaw.cli.util.readFileText
import io.github.klaw.common.conversation.ConversationMessage
import io.github.klaw.common.paths.KlawPaths
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.serialization.json.Json
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.sleep

internal class LogsCommand(
    private val conversationsDir: String = KlawPaths.conversations,
) : CliktCommand(name = "logs") {
    private val follow by option("--follow", "-f").flag()
    private val chat by option("--chat")
    private val limit by option("--limit", "-n").int().default(50)

    private val json = Json { ignoreUnknownKeys = true }

    override fun run() {
        if (!fileExists(conversationsDir) || !isDirectory(conversationsDir)) return

        val chatDirs = resolveChatDirs()
        if (chatDirs.isEmpty()) return

        showRecentLogs(chatDirs, limit)

        if (follow) {
            tailLogs(chatDirs)
        }
    }

    private fun resolveChatDirs(): List<String> =
        if (chat != null) {
            if (fileExists("$conversationsDir/$chat") && isDirectory("$conversationsDir/$chat")) {
                listOf(chat!!)
            } else {
                emptyList()
            }
        } else {
            listDirectory(conversationsDir).filter { isDirectory("$conversationsDir/$it") }.sorted()
        }

    private fun showRecentLogs(
        chatDirs: List<String>,
        maxLines: Int,
    ) {
        val allMessages = mutableListOf<Pair<String, ConversationMessage>>()
        for (chatId in chatDirs) {
            val jsonlPath = "$conversationsDir/$chatId/$chatId.jsonl"
            if (!fileExists(jsonlPath)) continue
            val content = readFileText(jsonlPath) ?: continue
            content.lines().filter { it.isNotBlank() }.forEach { line ->
                try {
                    val msg = json.decodeFromString<ConversationMessage>(line)
                    allMessages += chatId to msg
                } catch (_: Exception) {
                    // skip malformed lines
                }
            }
        }
        allMessages.takeLast(maxLines).forEach { (chatId, msg) ->
            echo("[${msg.ts}] [$chatId] [${msg.role}] ${msg.content}")
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun tailLogs(initialChatDirs: List<String>) {
        val positions = mutableMapOf<String, Long>()
        for (chatId in initialChatDirs) {
            val path = "$conversationsDir/$chatId/$chatId.jsonl"
            positions[path] = getFileSize(path)
        }

        while (true) {
            sleep(1u)
            val currentDirs =
                if (chat != null) {
                    initialChatDirs
                } else {
                    listDirectory(conversationsDir).filter { isDirectory("$conversationsDir/$it") }.sorted()
                }
            for (chatId in currentDirs) {
                val path = "$conversationsDir/$chatId/$chatId.jsonl"
                val oldPos = positions[path] ?: 0L
                val newContent = readFileFromOffset(path, oldPos)
                if (newContent != null && newContent.isNotBlank()) {
                    positions[path] = getFileSize(path)
                    newContent.lines().filter { it.isNotBlank() }.forEach { line ->
                        try {
                            val msg = json.decodeFromString<ConversationMessage>(line)
                            echo("[${msg.ts}] [$chatId] [${msg.role}] ${msg.content}")
                        } catch (_: Exception) {
                            // skip malformed
                        }
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun getFileSize(path: String): Long {
        val file = fopen(path, "r") ?: return 0L
        fseek(file, 0, SEEK_END)
        val size = ftell(file)
        fclose(file)
        return size
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readFileFromOffset(
        path: String,
        offset: Long,
    ): String? {
        val file = fopen(path, "r") ?: return null
        fseek(file, offset, SEEK_SET)
        val sb = StringBuilder()
        val buf = ByteArray(4096)
        buf.usePinned { pinned ->
            while (true) {
                val n = fread(pinned.addressOf(0), 1.toULong(), buf.size.toULong(), file).toInt()
                if (n <= 0) break
                sb.append(buf.decodeToString(0, n))
            }
        }
        fclose(file)
        return if (sb.isEmpty()) null else sb.toString()
    }
}
