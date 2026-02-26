package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException

internal class SessionsCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "sessions") {
    override fun run() {
        try {
            echo(requestFn("sessions", emptyMap()))
        } catch (_: EngineNotRunningException) {
            echo("Engine is not running. Start it with: systemctl --user start klaw-engine")
        }
    }
}
