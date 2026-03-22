package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger

internal class StatusCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "status") {
    private val sessions by option("--sessions", help = "Include active session list").flag()

    override fun run() {
        CliLogger.debug { "requesting status" }
        try {
            echo(requestFn("status", emptyMap()))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
            return
        }
        if (sessions) {
            CliLogger.debug { "requesting sessions" }
            try {
                echo(requestFn("sessions", emptyMap()))
            } catch (_: EngineNotRunningException) {
                CliLogger.error { "engine not running" }
                echo("Engine is not running. Start it with: klaw service start engine")
            }
        }
    }
}
