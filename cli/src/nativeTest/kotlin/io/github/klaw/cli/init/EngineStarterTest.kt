package io.github.klaw.cli.init

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EngineStarterTest {
    @Test
    fun `returns true when port is responsive within timeout`() {
        val commandsRun = mutableListOf<String>()
        val starter =
            EngineStarter(
                enginePort = 7470,
                engineHost = "127.0.0.1",
                portChecker = { _, _ -> true },
                commandRunner = { cmd ->
                    commandsRun += cmd
                    0
                },
                pollIntervalMs = 10L,
                timeoutMs = 500L,
            )

        val result = starter.startAndWait()
        assertTrue(result, "Expected starter to return true when port is responsive")
    }

    @Test
    fun `returns false after timeout if port never responds`() {
        val starter =
            EngineStarter(
                enginePort = 7470,
                engineHost = "127.0.0.1",
                portChecker = { _, _ -> false },
                commandRunner = { _ -> 0 },
                pollIntervalMs = 10L,
                timeoutMs = 50L,
            )

        val result = starter.startAndWait()
        assertFalse(result, "Expected starter to return false when port never responds")
    }

    @Test
    fun `invokes start command before polling`() {
        val commandsRun = mutableListOf<String>()

        val starter =
            EngineStarter(
                enginePort = 7470,
                engineHost = "127.0.0.1",
                portChecker = { _, _ -> true },
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
