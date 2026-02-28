package io.github.klaw.cli.init

internal class DockerComposeInstaller(
    private val composeFile: String = "/app/docker-compose.json",
    private val printer: (String) -> Unit,
    private val commandRunner: (String) -> Int,
) {
    init {
        require("'" !in composeFile) { "composeFile path must not contain single-quote characters" }
    }

    fun installServices(): Boolean {
        printer("  Starting Engine and Gateway containers via Docker Compose...")
        return commandRunner("docker compose -f '$composeFile' up -d engine gateway") == 0
    }
}
