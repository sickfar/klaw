package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.klaw.cli.ui.AnsiColors
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.isDirectory
import io.github.klaw.cli.util.listDirectory
import io.github.klaw.cli.util.readFileText
import io.github.klaw.common.conversation.ConversationMessage
import io.github.klaw.common.paths.KlawPaths
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import platform.posix.SEEK_END
import platform.posix.SEEK_SET
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.usleep

private const val DEFAULT_LOG_LIMIT = 50
private const val DEFAULT_POLL_INTERVAL = 1000
private const val TAIL_READ_BUF_SIZE = 4096
private const val TIMEOUT_POLL_INTERVAL_MS = 100
private const val MS_TO_MICROSECONDS = 1000
private const val MS_PER_SECOND = 1000L

internal class LogsCommand(
    private val conversationsDir: String = KlawPaths.conversations,
) : CliktCommand(name = "logs") {
    private val follow by option("--follow", "-f").flag()
    private val chat by option("--chat")
    private val limit by option("--limit", "-n").int().default(DEFAULT_LOG_LIMIT)
    private val jsonOutput by option("--json").flag()
    private val interval by option("--interval").int().default(DEFAULT_POLL_INTERVAL)
    private val maxBytes by option("--max-bytes").int()
    private val localTime by option("--local-time").flag()
    private val noColor by option("--no-color").flag()
    private val timeout by option("--timeout").int()

    private val json = Json { ignoreUnknownKeys = true }

    private val useColor: Boolean get() = !noColor && !jsonOutput

    override fun run() {
        if (!conversationsDirExists()) {
            handleTimeout()
            return
        }

        val chatDirs = resolveChatDirs()
        if (chatDirs.isEmpty()) {
            handleTimeout()
            return
        }
        CliLogger.debug { "resolved ${chatDirs.size} chat dir(s), follow=$follow" }

        showRecentLogs(chatDirs, limit)

        if (follow) tailLogs(chatDirs)
    }

    private fun conversationsDirExists(): Boolean = fileExists(conversationsDir) && isDirectory(conversationsDir)

    @OptIn(ExperimentalForeignApi::class)
    private fun handleTimeout() {
        val timeoutMs = timeout ?: return
        val deadline = getWallTimeMs() + timeoutMs
        while (getWallTimeMs() < deadline) {
            usleep((TIMEOUT_POLL_INTERVAL_MS * MS_TO_MICROSECONDS).toUInt())
            val dirs = tryResolveDirsWithData() ?: continue
            showRecentLogs(dirs, limit)
            if (follow) tailLogs(dirs)
            return
        }
        CliLogger.warn { "no conversation directories found" }
    }

    private fun tryResolveDirsWithData(): List<String>? {
        if (!conversationsDirExists()) return null
        val dirs = resolveChatDirs()
        if (dirs.isEmpty()) return null
        return dirs.takeIf { findJsonlFiles(it).isNotEmpty() }
    }

    private fun resolveChatDirs(): List<String> =
        if (chat != null) {
            val sanitized = sanitizeChatId(chat!!) ?: return emptyList()
            if (fileExists("$conversationsDir/$sanitized") && isDirectory("$conversationsDir/$sanitized")) {
                listOf(sanitized)
            } else {
                emptyList()
            }
        } else {
            listDirectory(conversationsDir).filter { isDirectory("$conversationsDir/$it") }.sorted()
        }

    private fun sanitizeChatId(chatId: String): String? {
        if (chatId.contains('/') || chatId.contains("..") || chatId.contains('\u0000')) return null
        return chatId
    }

    private fun listJsonlFiles(chatId: String): List<String> =
        listDirectory("$conversationsDir/$chatId").filter { it.endsWith(".jsonl") }.sorted()

    private fun findJsonlFiles(chatDirs: List<String>): List<Pair<String, String>> =
        chatDirs.flatMap { chatId -> listJsonlFiles(chatId).map { chatId to it } }

    private fun showRecentLogs(
        chatDirs: List<String>,
        maxLines: Int,
    ) {
        val allEntries = collectEntries(chatDirs)
        allEntries.takeLast(maxLines).forEach { printEntry(it) }
    }

    private fun collectEntries(chatDirs: List<String>): List<LogEntry> {
        val allEntries = mutableListOf<LogEntry>()
        var bytesRead = 0L
        val byteBudget = maxBytes

        for (chatId in chatDirs) {
            if (byteBudget != null && bytesRead >= byteBudget) break
            for (fileName in listJsonlFiles(chatId)) {
                if (byteBudget != null && bytesRead >= byteBudget) break
                bytesRead += readFileEntries(chatId, fileName, byteBudget, bytesRead, allEntries)
            }
        }
        return allEntries
    }

    private fun readFileEntries(
        chatId: String,
        fileName: String,
        byteBudget: Int?,
        bytesRead: Long,
        entries: MutableList<LogEntry>,
    ): Long {
        val filePath = "$conversationsDir/$chatId/$fileName"
        val content = readFileText(filePath) ?: return 0L
        val effectiveContent = applyByteBudget(content, byteBudget, bytesRead)
        entries += parseContentLines(effectiveContent, chatId)
        return effectiveContent.encodeToByteArray().size.toLong()
    }

    private fun applyByteBudget(
        content: String,
        byteBudget: Int?,
        bytesRead: Long,
    ): String {
        if (byteBudget == null) return content
        val remaining = byteBudget - bytesRead
        val contentBytes = content.encodeToByteArray().size.toLong()
        if (contentBytes <= remaining) return content
        return content.substring(0, remaining.toInt().coerceAtMost(content.length))
    }

    private fun parseContentLines(
        content: String,
        chatId: String,
    ): List<LogEntry> {
        val entries = mutableListOf<LogEntry>()
        content.lines().filter { it.isNotBlank() }.forEach { line ->
            try {
                val msg = json.decodeFromString<ConversationMessage>(line)
                entries += LogEntry(chatId, msg, line)
            } catch (_: SerializationException) {
                // skip malformed JSONL lines
            }
        }
        return entries
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun tailLogs(initialChatDirs: List<String>) {
        val positions = initFilePositions(initialChatDirs)

        while (true) {
            usleep((interval * MS_TO_MICROSECONDS).toUInt())
            pollNewContent(resolveChatDirs(), positions)
        }
    }

    private fun initFilePositions(chatDirs: List<String>): MutableMap<String, Long> {
        val positions = mutableMapOf<String, Long>()
        for (chatId in chatDirs) {
            for (fileName in listJsonlFiles(chatId)) {
                val path = "$conversationsDir/$chatId/$fileName"
                positions[path] = getFileSize(path)
            }
        }
        return positions
    }

    private fun pollNewContent(
        chatDirs: List<String>,
        positions: MutableMap<String, Long>,
    ) {
        for (chatId in chatDirs) {
            for (fileName in listJsonlFiles(chatId)) {
                val path = "$conversationsDir/$chatId/$fileName"
                val oldPos = positions[path] ?: 0L
                val newContent = readFileFromOffset(path, oldPos)
                if (newContent != null && newContent.isNotBlank()) {
                    positions[path] = oldPos + newContent.encodeToByteArray().size
                    processNewLines(chatId, newContent)
                }
            }
        }
    }

    private fun processNewLines(
        chatId: String,
        content: String,
    ) {
        parseContentLines(content, chatId).forEach { printEntry(it) }
    }

    private fun printEntry(entry: LogEntry) {
        if (jsonOutput) {
            echo(entry.rawLine)
            return
        }
        val ts = formatTimestamp(entry.msg.ts)
        val chatId = entry.chatId
        val role = entry.msg.role

        if (useColor) {
            echo(
                "${AnsiColors.CYAN}[$ts]${AnsiColors.RESET} " +
                    "${AnsiColors.GREEN}[$chatId]${AnsiColors.RESET} " +
                    "${colorRole(role)} ${entry.msg.content}",
            )
        } else {
            echo("[$ts] [$chatId] [$role] ${entry.msg.content}")
        }
    }

    private fun colorRole(role: String): String =
        when (role) {
            "user" -> "${AnsiColors.YELLOW}[$role]${AnsiColors.RESET}"
            "assistant" -> "${AnsiColors.GREEN}${AnsiColors.BOLD}[$role]${AnsiColors.RESET}"
            else -> "[$role]"
        }

    private fun formatTimestamp(ts: String): String {
        if (!localTime) return ts
        return try {
            val instant = kotlin.time.Instant.parse(ts)
            instant.toLocalDateTime(TimeZone.currentSystemDefault()).toString()
        } catch (_: Exception) {
            ts
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun getFileSize(path: String): Long {
        val file = fopen(path, "r") ?: return 0L
        return try {
            fseek(file, 0, SEEK_END)
            ftell(file)
        } finally {
            fclose(file)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun readFileFromOffset(
        path: String,
        offset: Long,
    ): String? {
        val file = fopen(path, "r") ?: return null
        return try {
            fseek(file, offset, SEEK_SET)
            val sb = StringBuilder()
            val buf = ByteArray(TAIL_READ_BUF_SIZE)
            buf.usePinned { pinned ->
                while (true) {
                    val n = fread(pinned.addressOf(0), 1.toULong(), buf.size.toULong(), file).toInt()
                    if (n <= 0) break
                    sb.append(buf.decodeToString(0, n))
                }
            }
            if (sb.isEmpty()) null else sb.toString()
        } finally {
            fclose(file)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun getWallTimeMs(): Long = platform.posix.time(null) * MS_PER_SECOND

    private data class LogEntry(
        val chatId: String,
        val msg: ConversationMessage,
        val rawLine: String,
    )
}
