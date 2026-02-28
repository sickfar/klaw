package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger

internal class SessionsCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "sessions") {
    override fun run() {
        CliLogger.debug { "requesting sessions" }
        try {
            echo(requestFn("sessions", emptyMap()))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: systemctl --user start klaw-engine")
        }
    }
}
