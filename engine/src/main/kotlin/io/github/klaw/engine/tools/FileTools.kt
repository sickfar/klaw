package io.github.klaw.engine.tools

import io.github.klaw.engine.util.VT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

@Suppress("TooGenericExceptionCaught", "ReturnCount")
class FileTools(
    private val workspace: Path,
    private val maxFileSizeBytes: Long,
) {
    private fun safePath(userPath: String): Result<Path> {
        return try {
            val resolved = workspace.resolve(userPath).normalize()
            if (!resolved.startsWith(workspace)) {
                return Result.failure(SecurityException("Access denied: path outside workspace"))
            }
            // Check for symlinks (including broken ones) at the path itself
            if (Files.isSymbolicLink(resolved)) {
                val real = resolved.toRealPath()
                val workspaceReal = workspace.toRealPath()
                if (!real.startsWith(workspaceReal)) {
                    return Result.failure(SecurityException("Access denied: path outside workspace"))
                }
            }
            // Check parent chain for symlinks pointing outside workspace
            checkParentChain(resolved)?.let { return Result.failure(it) }
            // Check existing non-symlink paths via toRealPath
            if (Files.exists(resolved)) {
                val real = resolved.toRealPath()
                val workspaceReal = workspace.toRealPath()
                if (!real.startsWith(workspaceReal)) {
                    return Result.failure(SecurityException("Access denied: path outside workspace"))
                }
            }
            Result.success(resolved)
        } catch (e: Exception) {
            Result.failure(SecurityException("Access denied: ${e.message}"))
        }
    }

    private fun checkParentChain(path: Path): SecurityException? {
        var current = path.parent
        val workspaceReal = workspace.toRealPath()
        while (current != null && current.startsWith(workspace)) {
            if (Files.isSymbolicLink(current)) {
                val realParent = current.toRealPath()
                if (!realParent.startsWith(workspaceReal)) {
                    return SecurityException("Access denied: path outside workspace")
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
                "Error reading file: ${e.message}"
            }
        }
    }

    suspend fun write(
        path: String,
        content: String,
        mode: String,
    ): String {
        if (content.toByteArray().size > maxFileSizeBytes) {
            return "Error: content exceeds maximum file size of $maxFileSizeBytes bytes"
        }
        val safePath = safePath(path).getOrElse { return it.message ?: "Access denied" }
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
                "Error writing file: ${e.message}"
            }
        }
    }

    suspend fun list(
        path: String,
        recursive: Boolean = false,
    ): String {
        val safePath = safePath(path).getOrElse { return it.message ?: "Access denied" }
        return withContext(Dispatchers.VT) {
            try {
                if (!Files.exists(safePath) || !Files.isDirectory(safePath)) {
                    return@withContext "Error: directory not found: $path"
                }
                val stream = if (recursive) Files.walk(safePath) else Files.list(safePath)
                stream.use { s ->
                    s
                        .filter { it != safePath }
                        .map { workspace.relativize(it).toString() + if (Files.isDirectory(it)) "/" else "" }
                        .sorted()
                        .toList()
                        .joinToString("\n")
                }
            } catch (e: Exception) {
                "Error listing directory: ${e.message}"
            }
        }
    }
}
