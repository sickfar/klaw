package io.github.klaw.engine.mcp

object StackDetector {
    private val stackImages =
        mapOf(
            "npx" to "node:22-alpine",
            "node" to "node:22-alpine",
            "uvx" to "python:3.12-slim",
            "python" to "python:3.12-slim",
            "python3" to "python:3.12-slim",
        )

    fun resolve(command: String): String? = stackImages[command]

    fun isDockerCommand(command: String): Boolean = command == "docker"
}
