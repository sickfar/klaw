package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger

internal class ReindexCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "reindex") {
    override fun run() {
        CliLogger.debug { "requesting reindex" }
        try {
            echo(requestFn("reindex", emptyMap()))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: systemctl --user start klaw-engine")
        }
    }
}
