package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.util.writeFileText
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IdentityCommandTest {
    private val tmpDir = "/tmp/klaw-identity-test-${getpid()}"

    @BeforeTest
    fun setup() {
        platform.posix.mkdir(tmpDir, 0x1EDu)
        writeFileText("$tmpDir/SOUL.md", "# Soul")
        writeFileText("$tmpDir/IDENTITY.md", "# Identity")
    }

    @AfterTest
    fun cleanup() {
        platform.posix.unlink("$tmpDir/SOUL.md")
        platform.posix.unlink("$tmpDir/IDENTITY.md")
        platform.posix.rmdir(tmpDir)
    }

    @Test
    fun `identity edit invokes system editor for identity files`() {
        val commands = mutableListOf<String>()
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                workspaceDir = tmpDir,
                commandRunner = { cmd ->
                    commands += cmd
                    0
                },
            )
        val result = cli.test("identity edit")
        assertEquals(0, result.statusCode, "Expected exit 0: ${result.output}")
        assertTrue(commands.any { it.contains("SOUL.md") }, "Expected SOUL.md in commands: $commands")
        assertTrue(commands.any { it.contains("IDENTITY.md") }, "Expected IDENTITY.md in commands: $commands")
    }

    @Test
    fun `identity edit prints message when workspace files missing`() {
        val cli =
            KlawCli(
                requestFn = { _, _ -> "{}" },
                workspaceDir = "/nonexistent/workspace",
                commandRunner = { _ -> 0 },
            )
        val result = cli.test("identity edit")
        assertEquals(0, result.statusCode, "Should not crash: ${result.output}")
    }
}
