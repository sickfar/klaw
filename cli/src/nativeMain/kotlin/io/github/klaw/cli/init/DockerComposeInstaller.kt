package io.github.klaw.cli.init

import io.github.klaw.cli.util.CliLogger

internal class DockerComposeInstaller(
    private val composeFile: String = "/app/docker-compose.json",
    private val printer: (String) -> Unit,
    private val commandRunner: (String) -> Int,
) {
    init {
        require("'" !in composeFile) { "composeFile path must not contain single-quote characters" }
    }

    fun installServices(): Boolean {
        CliLogger.info { "starting containers via docker compose" }
        printer("  Starting Engine and Gateway containers via Docker Compose...")
        val result = commandRunner("docker compose -f '$composeFile' up -d engine gateway") == 0
        if (!result) {
            CliLogger.error { "docker compose up failed" }
        }
        return result
    }
}
