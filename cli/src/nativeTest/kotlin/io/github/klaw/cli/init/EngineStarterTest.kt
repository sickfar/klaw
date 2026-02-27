package io.github.klaw.cli.init

import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineStarterTest {
    private val tmpDir = "/tmp/klaw-starter-test-${getpid()}"
    private val socketPath = "$tmpDir/engine.sock"

    @BeforeTest
    fun setup() {
        platform.posix.mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        platform.posix.unlink(socketPath)
        platform.posix.rmdir(tmpDir)
    }

    @Test
    fun `returns true when socket appears within timeout`() {
        val commandsRun = mutableListOf<String>()
        val starter =
            EngineStarter(
                engineSocketPath = socketPath,
                commandRunner = { cmd ->
                    commandsRun += cmd
                    0
                },
                pollIntervalMs = 10L,
                timeoutMs = 500L,
            )

        // Create socket file after slight delay from a background thread — simulate engine starting
        // We create it immediately since we can't do real async in native tests
        platform.posix.creat(socketPath, 0x1A4u) // 0644, creates the file

        val result = starter.startAndWait()
        assertTrue(result, "Expected starter to return true when socket exists")
    }

    @Test
    fun `returns false after timeout if socket never appears`() {
        val starter =
            EngineStarter(
                engineSocketPath = socketPath,
                commandRunner = { _ -> 0 },
                pollIntervalMs = 10L,
                timeoutMs = 50L,
            )

        val result = starter.startAndWait()
        assertFalse(result, "Expected starter to return false when socket never appears")
    }

    @Test
    fun `invokes start command before polling`() {
        val commandsRun = mutableListOf<String>()
        platform.posix.creat(socketPath, 0x1A4u) // socket already there

        val starter =
            EngineStarter(
                engineSocketPath = socketPath,
                commandRunner = { cmd ->
                    commandsRun += cmd
                    0
                },
                pollIntervalMs = 10L,
                timeoutMs = 500L,
            )

        starter.startAndWait()
        assertTrue(commandsRun.isNotEmpty(), "Expected at least one command to be run")
    }
}
