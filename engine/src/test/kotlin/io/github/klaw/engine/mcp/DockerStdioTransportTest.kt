package io.github.klaw.engine.mcp

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DockerStdioTransportTest {
    private fun params(
        serverName: String = "srv",
        image: String = "node:22-alpine",
        command: String = "npx",
        args: List<String> = emptyList(),
        env: Map<String, String> = emptyMap(),
        network: String? = null,
    ) = DockerStdioParams(serverName, image, command, args, env, network)

    @Test
    fun `buildDockerCommand constructs correct command`() {
        val cmd =
            DockerStdioTransport.buildDockerCommand(
                params(
                    serverName = "test-server",
                    image = "node:22-alpine",
                    command = "npx",
                    args = listOf("-y", "some-mcp-server"),
                ),
            )

        assertEquals(
            listOf(
                "docker",
                "run",
                "-i",
                "--rm",
                "--name",
                "klaw-mcp-test-server",
                "node:22-alpine",
                "npx",
                "-y",
                "some-mcp-server",
            ),
            cmd,
        )
    }

    @Test
    fun `buildDockerCommand includes env vars`() {
        val cmd =
            DockerStdioTransport.buildDockerCommand(
                params(
                    image = "python:3.12-slim",
                    command = "uvx",
                    args = listOf("mcp-server"),
                    env = mapOf("API_KEY" to "secret", "PORT" to "8080"),
                ),
            )

        val envFlags = cmd.indices.filter { cmd[it] == "-e" }
        assertEquals(2, envFlags.size)
    }

    @Test
    fun `buildDockerCommand includes network when provided`() {
        val cmd =
            DockerStdioTransport.buildDockerCommand(
                params(
                    command = "node",
                    args = listOf("server.js"),
                    network = "klaw-net",
                ),
            )

        val netIdx = cmd.indexOf("--network")
        assert(netIdx >= 0) { "Expected --network flag" }
        assertEquals("klaw-net", cmd[netIdx + 1])
    }

    @Test
    fun `buildDockerCommand with empty args`() {
        val cmd =
            DockerStdioTransport.buildDockerCommand(
                params(
                    serverName = "minimal",
                    image = "alpine:latest",
                    command = "cat",
                ),
            )

        assertEquals(
            listOf(
                "docker",
                "run",
                "-i",
                "--rm",
                "--name",
                "klaw-mcp-minimal",
                "alpine:latest",
                "cat",
            ),
            cmd,
        )
    }
}
