package io.github.klaw.cli.init

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerComposeInstallerTest {
    @Test
    fun `installServices runs docker compose up for both services`() {
        val commandsRun = mutableListOf<String>()
        val installer =
            DockerComposeInstaller(
                composeFile = "/app/docker-compose.json",
                printer = {},
                commandRunner = { cmd ->
                    commandsRun += cmd
                    0
                },
            )

        val result = installer.installServices()

        assertTrue(result, "Expected installServices to return true on success")
        assertEquals(1, commandsRun.size, "Expected exactly one command")
        assertTrue(
            commandsRun[0].contains("up -d engine gateway"),
            "Command should start both services: ${commandsRun[0]}",
        )
    }

    @Test
    fun `installServices returns false when command fails`() {
        val installer =
            DockerComposeInstaller(
                composeFile = "/app/docker-compose.json",
                printer = {},
                commandRunner = { _ -> 127 },
            )

        val result = installer.installServices()

        assertFalse(result, "Expected installServices to return false when command fails")
    }

    @Test
    fun `constructor rejects compose file path with single quote`() {
        assertFails("Expected IllegalArgumentException for path with single-quote") {
            DockerComposeInstaller(
                composeFile = "/app/docker'-compose.json",
                printer = {},
                commandRunner = { _ -> 0 },
            )
        }
    }
}
