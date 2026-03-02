package io.github.klaw.engine.tools

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class FileToolsTest {
    @TempDir
    lateinit var workspace: Path

    @TempDir
    lateinit var stateDir: Path

    private fun tools(maxSize: Long = 10_000L): FileTools = FileTools(listOf(workspace), maxSize)

    private fun toolsMultiPath(maxSize: Long = 10_000L): FileTools = FileTools(listOf(workspace, stateDir), maxSize)

    @Test
    fun `file_read reads file from workspace`() =
        runTest {
            Files.writeString(workspace.resolve("hello.txt"), "hello world")
            val result = tools().read("hello.txt")
            assertEquals("hello world", result)
        }

    @Test
    fun `file_read with startLine and maxLines`() =
        runTest {
            Files.writeString(workspace.resolve("lines.txt"), "line1\nline2\nline3\nline4\nline5")
            val result = tools().read("lines.txt", startLine = 2, maxLines = 2)
            assertEquals("line2\nline3", result)
        }

    @Test
    fun `file_read REJECTS path traversal with dotdot`() =
        runTest {
            val result = tools().read("../../etc/passwd")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
        }

    @Test
    fun `file_read REJECTS absolute path outside workspace`() =
        runTest {
            val result = tools().read("/etc/passwd")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
        }

    @Test
    fun `file_read returns error for non-existent file`() =
        runTest {
            val result = tools().read("no-such-file.txt")
            assertTrue(result.contains("Error") || result.contains("not found"), "Expected error but got: $result")
        }

    @Test
    fun `file_write creates new file`() =
        runTest {
            val result = tools().write("new.txt", "content here", "overwrite")
            assertTrue(result.startsWith("OK"), "Expected OK but got: $result")
            assertEquals("content here", Files.readString(workspace.resolve("new.txt")))
        }

    @Test
    fun `file_write creates parent directories`() =
        runTest {
            val result = tools().write("sub/dir/file.txt", "nested", "overwrite")
            assertTrue(result.startsWith("OK"))
            assertEquals("nested", Files.readString(workspace.resolve("sub/dir/file.txt")))
        }

    @Test
    fun `file_write append mode`() =
        runTest {
            tools().write("append.txt", "first", "overwrite")
            tools().write("append.txt", " second", "append")
            assertEquals("first second", Files.readString(workspace.resolve("append.txt")))
        }

    @Test
    fun `file_write overwrite mode`() =
        runTest {
            tools().write("over.txt", "original", "overwrite")
            tools().write("over.txt", "replaced", "overwrite")
            assertEquals("replaced", Files.readString(workspace.resolve("over.txt")))
        }

    @Test
    fun `file_write REJECTS content larger than maxFileSizeBytes`() =
        runTest {
            val result = tools(maxSize = 10).write("big.txt", "a".repeat(100), "overwrite")
            assertTrue(result.contains("exceeds"), "Expected size error but got: $result")
        }

    @Test
    fun `file_write REJECTS path outside workspace`() =
        runTest {
            val result = tools().write("../../evil.txt", "bad", "overwrite")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
        }

    @Test
    fun `file_list directory listing`() =
        runTest {
            Files.writeString(workspace.resolve("a.txt"), "a")
            Files.writeString(workspace.resolve("b.txt"), "b")
            val result = tools().list(".")
            assertTrue(result.contains("a.txt"), "Expected a.txt in: $result")
            assertTrue(result.contains("b.txt"), "Expected b.txt in: $result")
        }

    @Test
    fun `file_list recursive listing`() =
        runTest {
            val sub = workspace.resolve("sub")
            Files.createDirectories(sub)
            Files.writeString(sub.resolve("deep.txt"), "deep")
            val result = tools().list(".", recursive = true)
            assertTrue(result.contains("deep.txt"), "Expected deep.txt in: $result")
        }

    @Test
    fun `file_list returns error for non-existent path`() =
        runTest {
            val result = tools().list("no-such-dir")
            assertTrue(result.contains("Error") || result.contains("not found"), "Expected error but got: $result")
        }

    @Test
    fun `symlink outside workspace is rejected`() =
        runTest {
            val outside = Files.createTempFile("outside", ".txt")
            Files.writeString(outside, "secret")
            val link = workspace.resolve("sneaky-link")
            Files.createSymbolicLink(link, outside)
            val result = tools().read("sneaky-link")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
            Files.deleteIfExists(outside)
        }

    @Test
    fun `broken symlink write-through is rejected`() =
        runTest {
            // Broken symlink pointing outside workspace — target does not exist
            val link = workspace.resolve("broken-link")
            Files.createSymbolicLink(link, Path.of("/tmp/klaw-nonexistent-target"))
            val result = tools().write("broken-link", "payload", "overwrite")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
        }

    @Test
    fun `directory symlink outside workspace is rejected`() =
        runTest {
            val outsideDir = Files.createTempDirectory("outside-dir")
            val dirLink = workspace.resolve("dir-link")
            Files.createSymbolicLink(dirLink, outsideDir)
            val result = tools().write("dir-link/file.txt", "payload", "overwrite")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
            Files.deleteIfExists(outsideDir)
        }

    @Test
    fun `read IOException error contains class name not raw path`() =
        runTest {
            val file = workspace.resolve("no-read.txt")
            Files.writeString(file, "content")
            Files.setPosixFilePermissions(file, emptySet())
            try {
                val result = tools().read("no-read.txt")
                assertTrue(result.startsWith("Error reading file:"), "Expected error prefix, got: $result")
                // e::class.simpleName returns "AccessDeniedException"
                assertTrue(result.contains("AccessDeniedException"), "Expected class name in error, got: $result")
                // e.message returns the file path — it must NOT appear in the result
                assertFalse(
                    result.contains(file.toString()),
                    "Error must not expose raw exception message (file path), got: $result",
                )
            } finally {
                Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-rw-"))
            }
        }

    @Test
    fun `write IOException error contains class name not raw path`() =
        runTest {
            val dir = workspace.resolve("no-write-dir")
            Files.createDirectories(dir)
            Files.setPosixFilePermissions(dir, emptySet())
            val expectedPath = dir.resolve("file.txt").toString()
            try {
                val result = tools().write("no-write-dir/file.txt", "content", "overwrite")
                assertTrue(result.startsWith("Error writing file:"), "Expected error prefix, got: $result")
                assertTrue(result.contains("AccessDeniedException"), "Expected class name in error, got: $result")
                assertFalse(
                    result.contains(expectedPath),
                    "Error must not expose raw exception message (file path), got: $result",
                )
            } finally {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"))
            }
        }

    @Test
    fun `list IOException error contains class name not raw path`() =
        runTest {
            val dir = workspace.resolve("no-list-dir")
            Files.createDirectories(dir)
            Files.setPosixFilePermissions(dir, emptySet())
            try {
                val result = tools().list("no-list-dir")
                assertTrue(result.startsWith("Error listing directory:"), "Expected error prefix, got: $result")
                assertTrue(result.contains("AccessDeniedException"), "Expected class name in error, got: $result")
                assertFalse(
                    result.contains(dir.toString()),
                    "Error must not expose raw exception message (dir path), got: $result",
                )
            } finally {
                Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxrwxrwx"))
            }
        }

    // --- file_patch tests ---

    @Test
    fun `file_patch single occurrence replaced correctly`() =
        runTest {
            Files.writeString(workspace.resolve("hello.txt"), "hello world")
            val result = tools().patch("hello.txt", "world", "earth")
            assertTrue(result.startsWith("OK"), "Expected OK but got: $result")
            assertEquals("hello earth", Files.readString(workspace.resolve("hello.txt")))
        }

    @Test
    fun `file_patch old_string not found returns error`() =
        runTest {
            Files.writeString(workspace.resolve("hello.txt"), "hello world")
            val result = tools().patch("hello.txt", "missing", "replacement")
            assertTrue(result.contains("not found"), "Expected 'not found' error but got: $result")
        }

    @Test
    fun `file_patch multiple occurrences without force_first returns error with count`() =
        runTest {
            Files.writeString(workspace.resolve("multi.txt"), "aaa bbb aaa")
            val result = tools().patch("multi.txt", "aaa", "ccc")
            assertTrue(result.contains("2"), "Expected count in error but got: $result")
            assertTrue(result.contains("force_first"), "Expected force_first hint but got: $result")
        }

    @Test
    fun `file_patch multiple occurrences with force_first replaces first only`() =
        runTest {
            Files.writeString(workspace.resolve("multi.txt"), "aaa bbb aaa")
            val result = tools().patch("multi.txt", "aaa", "ccc", forceFirst = true)
            assertTrue(result.startsWith("OK"), "Expected OK but got: $result")
            assertEquals("ccc bbb aaa", Files.readString(workspace.resolve("multi.txt")))
        }

    @Test
    fun `file_patch empty old_string returns error`() =
        runTest {
            Files.writeString(workspace.resolve("hello.txt"), "hello world")
            val result = tools().patch("hello.txt", "", "something")
            assertTrue(result.contains("empty"), "Expected empty error but got: $result")
        }

    @Test
    fun `file_patch old_string equals new_string returns error`() =
        runTest {
            Files.writeString(workspace.resolve("hello.txt"), "hello world")
            val result = tools().patch("hello.txt", "world", "world")
            assertTrue(result.contains("identical"), "Expected identical error but got: $result")
        }

    @Test
    fun `file_patch file not found returns error`() =
        runTest {
            val result = tools().patch("no-such-file.txt", "old", "new")
            assertTrue(result.contains("not found"), "Expected not found error but got: $result")
        }

    @Test
    fun `file_patch path traversal returns access denied`() =
        runTest {
            val result = tools().patch("../../etc/passwd", "old", "new")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
        }

    @Test
    fun `file_patch result exceeds max file size returns error`() =
        runTest {
            Files.writeString(workspace.resolve("small.txt"), "ab")
            val result = tools(maxSize = 5).patch("small.txt", "ab", "a".repeat(10))
            assertTrue(result.contains("exceeds"), "Expected size error but got: $result")
            // Original file should be unchanged
            assertEquals("ab", Files.readString(workspace.resolve("small.txt")))
        }

    // --- Multi-path (read-only access to non-workspace directories) ---

    @Test
    fun `file_read reads from second allowed path using absolute path`() =
        runTest {
            val logFile = stateDir.resolve("logs").also { Files.createDirectories(it) }.resolve("engine.log")
            Files.writeString(logFile, "log line 1\nlog line 2")
            val result = toolsMultiPath().read(logFile.toAbsolutePath().toString())
            assertEquals("log line 1\nlog line 2", result)
        }

    @Test
    fun `file_read rejects path outside all allowed paths`() =
        runTest {
            val result = toolsMultiPath().read("/etc/passwd")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
        }

    @Test
    fun `file_write rejects writes to non-workspace allowed path`() =
        runTest {
            val logsDir = stateDir.resolve("logs").also { Files.createDirectories(it) }
            val absPath = logsDir.resolve("evil.txt").toAbsolutePath().toString()
            val result = toolsMultiPath().write(absPath, "payload", "overwrite")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
        }

    @Test
    fun `file_list works from non-workspace allowed path using absolute path`() =
        runTest {
            val logsDir = stateDir.resolve("logs").also { Files.createDirectories(it) }
            Files.writeString(logsDir.resolve("engine.log"), "log data")
            val result = toolsMultiPath().list(logsDir.toAbsolutePath().toString())
            assertTrue(result.contains("engine.log"), "Expected engine.log in: $result")
        }

    @Test
    fun `file_patch rejects patches to non-workspace allowed path`() =
        runTest {
            val logFile = stateDir.resolve("logs").also { Files.createDirectories(it) }.resolve("engine.log")
            Files.writeString(logFile, "old content")
            val result = toolsMultiPath().patch(logFile.toAbsolutePath().toString(), "old", "new")
            assertTrue(result.contains("Access denied"), "Expected 'Access denied' but got: $result")
        }
}
