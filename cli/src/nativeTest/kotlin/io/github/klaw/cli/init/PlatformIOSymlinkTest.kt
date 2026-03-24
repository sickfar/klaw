package io.github.klaw.cli.init

import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class PlatformIOSymlinkTest {
    private val tmpDir = "/tmp/klaw-symlink-test-${getpid()}"
    private val targetFile = "$tmpDir/target.txt"
    private val linkFile = "$tmpDir/link.txt"

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1FFu) // 0777
        writeFileText(targetFile, "hello")
    }

    @AfterTest
    fun cleanup() {
        unlink(linkFile)
        unlink(targetFile)
        rmdir(tmpDir)
    }

    @Test
    fun `createSymlink creates symlink to target`() {
        createSymlink(targetFile, linkFile)
        val content = readFileText(linkFile)
        assertTrue(
            content == "hello",
            "Expected symlink to resolve to target content, got: $content",
        )
    }

    @Test
    fun `createSymlink replaces existing symlink`() {
        // Create initial symlink
        createSymlink(targetFile, linkFile)

        // Write a different target
        val target2 = "$tmpDir/target2.txt"
        writeFileText(target2, "world")

        // Replace symlink
        createSymlink(target2, linkFile)
        val content = readFileText(linkFile)
        assertTrue(
            content == "world",
            "Expected symlink to point to new target, got: $content",
        )
        unlink(target2)
    }
}
