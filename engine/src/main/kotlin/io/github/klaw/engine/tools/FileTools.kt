package io.github.klaw.engine.tools

import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

private val logger = KotlinLogging.logger {}

@Suppress("TooGenericExceptionCaught", "ReturnCount", "NestedBlockDepth", "LoopWithTooManyJumpStatements")
class FileTools(
    private val allowedPaths: List<Path>,
    private val maxFileSizeBytes: Long,
    private val placeholders: Map<String, String> = emptyMap(),
) {
    // First allowed path is the workspace — the only writable path
    private val workspace: Path get() = allowedPaths.first()

    fun resolveReadPath(userPath: String): Result<Path> = safePath(userPath, writeAccess = false)

    fun resolveWritePath(userPath: String): Result<Path> = safePath(userPath, writeAccess = true)

    private fun expandPlaceholders(path: String): String =
        placeholders.entries.fold(path) { p, (k, v) -> p.replace(k, v) }

    private fun safePath(
        userPath: String,
        writeAccess: Boolean = false,
    ): Result<Path> {
        return try {
            val expanded = expandPlaceholders(userPath)
            val basePaths = if (writeAccess) listOf(workspace) else allowedPaths
            for (base in basePaths) {
                val resolved = base.resolve(expanded).normalize()
                if (!resolved.startsWith(base)) continue
                // Check for symlinks at the path itself
                if (Files.isSymbolicLink(resolved)) {
                    val real = resolved.toRealPath()
                    val baseReal = base.toRealPath()
                    if (!real.startsWith(baseReal)) continue
                }
                // Check parent chain for symlinks pointing outside
                if (checkParentChain(resolved, base) != null) continue
                // Check existing non-symlink paths via toRealPath
                if (Files.exists(resolved)) {
                    val real = resolved.toRealPath()
                    val baseReal = base.toRealPath()
                    if (!real.startsWith(baseReal)) continue
                }
                return Result.success(resolved)
            }
            Result.failure(SecurityException("Access denied: path outside allowed directories"))
        } catch (e: Exception) {
            logger.trace { "file access denied: ${e::class.simpleName}" }
            Result.failure(SecurityException("Access denied: ${e::class.simpleName}"))
        }
    }

    private fun checkParentChain(
        path: Path,
        base: Path,
    ): SecurityException? {
        var current = path.parent
        val baseReal = base.toRealPath()
        while (current != null && current.startsWith(base)) {
            if (Files.isSymbolicLink(current)) {
                val realParent = current.toRealPath()
                if (!realParent.startsWith(baseReal)) {
                    return SecurityException("Access denied: path outside allowed directories")
                }
            }
            current = current.parent
        }
        return null
    }

    suspend fun read(
        path: String,
        startLine: Int? = null,
        maxLines: Int? = null,
    ): String {
        logger.trace { "file_read: startLine=$startLine, maxLines=$maxLines" }
        val safePath = safePath(path).getOrElse { return it.message ?: "Access denied" }
        return withContext(Dispatchers.VT) {
            try {
                if (!Files.exists(safePath)) return@withContext "Error: file not found: $path"
                val lines = Files.readAllLines(safePath)
                val start = (startLine ?: 1).coerceAtLeast(1) - 1
                val end = if (maxLines != null) (start + maxLines).coerceAtMost(lines.size) else lines.size
                if (start >= lines.size) return@withContext ""
                lines.subList(start, end).joinToString("\n")
            } catch (e: Exception) {
                logger.warn(e) { "file_read failed" }
                "Error reading file: ${e::class.simpleName}"
            }
        }
    }

    suspend fun write(
        path: String,
        content: String,
        mode: String,
    ): String {
        logger.trace { "file_write: ${content.toByteArray().size} bytes, mode=$mode" }
        if (content.toByteArray().size > maxFileSizeBytes) {
            return "Error: content exceeds maximum file size of $maxFileSizeBytes bytes"
        }
        val safePath = safePath(path, writeAccess = true).getOrElse { return it.message ?: "Access denied" }
        return withContext(Dispatchers.VT) {
            try {
                val parent = safePath.parent
                if (parent != null && !Files.exists(parent)) {
                    Files.createDirectories(parent)
                }
                when (mode) {
                    "append" -> {
                        Files.writeString(
                            safePath,
                            content,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND,
                        )
                    }

                    else -> {
                        Files.writeString(safePath, content)
                    }
                }
                "OK: file written to $path"
            } catch (e: Exception) {
                logger.warn(e) { "file_write failed" }
                "Error writing file: ${e::class.simpleName}"
            }
        }
    }

    suspend fun patch(
        path: String,
        oldString: String,
        newString: String,
        forceFirst: Boolean = false,
    ): String {
        logger.trace { "file_patch: oldLen=${oldString.length}, newLen=${newString.length}, forceFirst=$forceFirst" }
        if (oldString.isEmpty()) return "Error: old_string must not be empty"
        if (oldString == newString) return "Error: old_string and new_string are identical"
        val safePath = safePath(path, writeAccess = true).getOrElse { return it.message ?: "Access denied" }
        return withContext(Dispatchers.VT) {
            try {
                if (!Files.exists(safePath)) return@withContext "Error: file not found: $path"
                val content = Files.readString(safePath)
                val count = countOccurrences(content, oldString)
                when {
                    count == 0 -> {
                        "Error: old_string not found in $path"
                    }

                    count > 1 && !forceFirst -> {
                        "Error: found $count occurrences of old_string in $path, use force_first=true to replace first"
                    }

                    else -> {
                        val newContent = content.replaceFirst(oldString, newString)
                        if (newContent.toByteArray().size > maxFileSizeBytes) {
                            return@withContext "Error: result exceeds maximum file size of $maxFileSizeBytes bytes"
                        }
                        Files.writeString(safePath, newContent)
                        "OK: patched $path"
                    }
                }
            } catch (e: Exception) {
                logger.warn(e) { "file_patch failed" }
                "Error patching file: ${e::class.simpleName}"
            }
        }
    }

    private fun countOccurrences(
        text: String,
        substring: String,
    ): Int {
        var count = 0
        var idx = 0
        while (true) {
            idx = text.indexOf(substring, idx)
            if (idx < 0) break
            count++
            idx += substring.length
        }
        return count
    }

    suspend fun list(
        path: String,
        recursive: Boolean = false,
    ): String {
        logger.trace { "file_list: recursive=$recursive" }
        val safePath = safePath(path).getOrElse { return it.message ?: "Access denied" }
        return withContext(Dispatchers.VT) {
            try {
                if (!Files.exists(safePath) || !Files.isDirectory(safePath)) {
                    return@withContext "Error: directory not found: $path"
                }
                // Find which base path contains this safePath for relativizing
                val matchedBase = allowedPaths.firstOrNull { safePath.startsWith(it) } ?: workspace
                val stream = if (recursive) Files.walk(safePath) else Files.list(safePath)
                stream.use { s ->
                    s
                        .filter { it != safePath }
                        .map { matchedBase.relativize(it).toString() + if (Files.isDirectory(it)) "/" else "" }
                        .sorted()
                        .toList()
                        .joinToString("\n")
                }
            } catch (e: Exception) {
                logger.warn(e) { "file_list failed" }
                "Error listing directory: ${e::class.simpleName}"
            }
        }
    }
}
