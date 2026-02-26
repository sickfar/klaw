package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException

internal class StatusCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "status") {
    override fun run() {
        try {
            echo(requestFn("status", emptyMap()))
        } catch (_: EngineNotRunningException) {
            echo("Engine is not running. Start it with: systemctl --user start klaw-engine")
        }
    }
}
