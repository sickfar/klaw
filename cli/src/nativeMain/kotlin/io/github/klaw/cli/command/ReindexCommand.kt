package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger

internal class ReindexCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "reindex") {
    private val fromJsonl by option("--from-jsonl", help = "Clear messages and rebuild from JSONL files")
        .flag(default = false)

    override fun run() {
        CliLogger.debug { "requesting reindex" }
        try {
            val params = if (fromJsonl) mapOf("from_jsonl" to "true") else emptyMap()
            echo(requestFn("reindex", params))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: systemctl --user start klaw-engine")
        }
    }
}
