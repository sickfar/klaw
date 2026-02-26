package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException

internal class ReindexCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "reindex") {
    override fun run() {
        try {
            echo(requestFn("reindex", emptyMap()))
        } catch (_: EngineNotRunningException) {
            echo("Engine is not running. Start it with: systemctl --user start klaw-engine")
        }
    }
}
