package io.github.klaw.cli.util

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.symlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalForeignApi::class)
class FileOpsTest {
    private val tmpDir = "/tmp/klaw-fileops-test-${getpid()}"

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1EDu) // 0755
    }

    @AfterTest
    fun cleanup() {
        // Use the function under test for cleanup (bootstrap: manually delete if test fails)
        deleteRecursively(tmpDir)
    }

    @Test
    fun `deleteRecursively removes empty directory`() {
        val dir = "$tmpDir/empty"
        mkdir(dir, 0x1EDu)
        assertTrue(isDirectory(dir))
        val result = deleteRecursively(dir)
        assertTrue(result)
        assertFalse(isDirectory(dir))
    }

    @Test
    fun `deleteRecursively removes nested dirs with files`() {
        val dir = "$tmpDir/nested"
        mkdir(dir, 0x1EDu)
        mkdir("$dir/sub", 0x1EDu)
        writeFileText("$dir/file.txt", "hello")
        writeFileText("$dir/sub/deep.txt", "world")
        assertTrue(fileExists("$dir/sub/deep.txt"))

        val result = deleteRecursively(dir)
        assertTrue(result)
        assertFalse(isDirectory(dir))
        assertFalse(fileExists("$dir/file.txt"))
    }

    @Test
    fun `deleteRecursively returns false for nonexistent path`() {
        val result = deleteRecursively("$tmpDir/nonexistent")
        assertFalse(result)
    }

    @Test
    fun `deleteRecursively unlinks symlink without following it`() {
        val target = "$tmpDir/target"
        mkdir(target, 0x1EDu)
        writeFileText("$target/important.txt", "do not delete")

        val link = "$tmpDir/link"
        symlink(target, link)
        assertTrue(isSymlink(link))

        val result = deleteRecursively(link)
        assertTrue(result, "deleteRecursively should succeed on symlink")
        assertFalse(isSymlink(link), "symlink should be removed")
        // Target and its contents must be preserved
        assertTrue(isDirectory(target), "symlink target dir should still exist")
        assertTrue(fileExists("$target/important.txt"), "symlink target contents should be preserved")
    }
}
