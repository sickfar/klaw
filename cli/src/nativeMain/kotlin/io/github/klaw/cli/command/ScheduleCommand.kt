package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException

internal class ScheduleCommand(
    requestFn: EngineRequest,
) : CliktCommand(name = "schedule") {
    init {
        subcommands(
            ScheduleListCommand(requestFn),
            ScheduleAddCommand(requestFn),
            ScheduleRemoveCommand(requestFn),
        )
    }

    override fun run() = Unit
}

internal class ScheduleListCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "list") {
    override fun run() {
        try {
            echo(requestFn("schedule_list", emptyMap()))
        } catch (_: EngineNotRunningException) {
            echo("Engine is not running. Start it with: systemctl --user start klaw-engine")
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
            echo("Engine is not running. Start it with: systemctl --user start klaw-engine")
        }
    }
}

internal class ScheduleRemoveCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "remove") {
    private val name by argument()

    override fun run() {
        try {
            echo(requestFn("schedule_remove", mapOf("name" to name)))
        } catch (_: EngineNotRunningException) {
            echo("Engine is not running. Start it with: systemctl --user start klaw-engine")
        }
    }
}
