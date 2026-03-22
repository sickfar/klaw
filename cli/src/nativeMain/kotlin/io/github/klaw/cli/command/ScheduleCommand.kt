package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger

internal class ScheduleCommand(
    requestFn: EngineRequest,
) : CliktCommand(name = "schedule") {
    init {
        subcommands(
            ScheduleListCommand(requestFn),
            ScheduleAddCommand(requestFn),
            ScheduleRemoveCommand(requestFn),
            ScheduleEditCommand(requestFn),
            ScheduleEnableCommand(requestFn),
            ScheduleDisableCommand(requestFn),
            ScheduleRunCommand(requestFn),
            ScheduleRunsCommand(requestFn),
            ScheduleStatusCommand(requestFn),
        )
    }

    override fun run() = Unit
}

internal class ScheduleListCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "list") {
    override fun run() {
        CliLogger.debug { "schedule list" }
        try {
            echo(requestFn("schedule_list", emptyMap()))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleAddCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "add") {
    private val name by argument()
    private val cron by argument()
    private val message by argument()
    private val model by option("--model")
    private val injectInto by option("--inject-into")

    override fun run() {
        CliLogger.debug { "schedule add name=$name" }
        try {
            val params =
                buildMap {
                    put("name", name)
                    put("cron", cron)
                    put("message", message)
                    model?.let { put("model", it) }
                    injectInto?.let { put("inject_into", it) }
                }
            echo(requestFn("schedule_add", params))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleRemoveCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "remove") {
    private val name by argument()

    override fun run() {
        CliLogger.debug { "schedule remove name=$name" }
        try {
            echo(requestFn("schedule_remove", mapOf("name" to name)))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleEditCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "edit") {
    private val name by argument()
    private val cron by option("--cron")
    private val message by option("--message")
    private val model by option("--model")

    override fun run() {
        CliLogger.debug { "schedule edit name=$name" }
        try {
            val params =
                buildMap {
                    put("name", name)
                    cron?.let { put("cron", it) }
                    message?.let { put("message", it) }
                    model?.let { put("model", it) }
                }
            echo(requestFn("schedule_edit", params))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleEnableCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "enable") {
    private val name by argument()

    override fun run() {
        CliLogger.debug { "schedule enable name=$name" }
        try {
            echo(requestFn("schedule_enable", mapOf("name" to name)))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleDisableCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "disable") {
    private val name by argument()

    override fun run() {
        CliLogger.debug { "schedule disable name=$name" }
        try {
            echo(requestFn("schedule_disable", mapOf("name" to name)))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleRunCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "run") {
    private val name by argument()

    override fun run() {
        CliLogger.debug { "schedule run name=$name" }
        try {
            echo(requestFn("schedule_run", mapOf("name" to name)))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleRunsCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "runs") {
    private val name by argument()
    private val limit by option("--limit").int().default(DEFAULT_RUNS_LIMIT)

    companion object {
        private const val DEFAULT_RUNS_LIMIT = 20
    }

    override fun run() {
        CliLogger.debug { "schedule runs name=$name limit=$limit" }
        try {
            echo(requestFn("schedule_runs", mapOf("name" to name, "limit" to limit.toString())))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}

internal class ScheduleStatusCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "status") {
    override fun run() {
        CliLogger.debug { "schedule status" }
        try {
            echo(requestFn("schedule_status", emptyMap()))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}
