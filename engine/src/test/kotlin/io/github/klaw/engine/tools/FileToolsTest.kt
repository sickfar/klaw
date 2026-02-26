package io.github.klaw.engine.tools

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileToolsTest {
    @TempDir
    lateinit var workspace: Path

    private fun tools(maxSize: Long = 10_000L): FileTools = FileTools(workspace, maxSize)

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
}
