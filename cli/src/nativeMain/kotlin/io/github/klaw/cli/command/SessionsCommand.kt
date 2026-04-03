package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger

internal class SessionsCommand(
    requestFn: EngineRequest,
) : CliktCommand(name = "sessions") {
    val agent by option("--agent", "-a", help = "Agent ID").default("default")

    init {
        subcommands(
            SessionsListCommand(requestFn),
            SessionsCleanupCommand(requestFn),
        )
    }

    override fun run() = Unit
}

internal class SessionsListCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "list") {
    private val active by option("--active", help = "Show only sessions active within N minutes").int()
    private val json by option("--json", help = "Output as JSON").flag()
    private val verbose by option("--verbose", help = "Show detailed info (tokens, timestamps)").flag()
    private val agentId: String get() = (currentContext.parent?.command as? SessionsCommand)?.agent ?: "default"

    override fun run() {
        val params =
            buildMap {
                active?.let { put("active_minutes", it.toString()) }
                if (json) put("json", "true")
                if (verbose) put("verbose", "true")
            }
        CliLogger.debug { "requesting sessions_list params=$params" }
        try {
            echo(requestFn("sessions_list", params, agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class SessionsCleanupCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "cleanup") {
    private val olderThan by option("--older-than", help = "Remove sessions inactive for N minutes (default: 1440)")
        .int()
    private val agentId: String get() = (currentContext.parent?.command as? SessionsCommand)?.agent ?: "default"

    override fun run() {
        val params =
            buildMap {
                olderThan?.let { put("older_than_minutes", it.toString()) }
            }
        CliLogger.debug { "requesting sessions_cleanup params=$params" }
        try {
            echo(requestFn("sessions_cleanup", params, agentId))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}
