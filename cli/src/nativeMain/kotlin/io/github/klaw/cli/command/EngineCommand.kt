@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.github.klaw.cli.init.KlawService
import io.github.klaw.cli.init.createServiceManager
import io.github.klaw.cli.util.CliLogger

internal class EngineCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "engine") {
    init {
        subcommands(
            EngineStartCommand(commandRunner, configDir),
            EngineStopCommand(commandRunner, configDir),
            EngineRestartCommand(commandRunner, configDir),
        )
    }

    override fun run() = Unit
}

private class EngineStartCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "start") {
    override fun run() {
        CliLogger.info { "engine start" }
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.start(KlawService.ENGINE)
        if (success) {
            CliLogger.info { "engine started" }
            echo("Engine started.")
        } else {
            CliLogger.error { "failed to start engine" }
            echo("Failed to start engine.")
        }
    }
}

private class EngineStopCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "stop") {
    override fun run() {
        CliLogger.info { "engine stop" }
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.stop(KlawService.ENGINE)
        if (success) {
            CliLogger.info { "engine stopped" }
            echo("Engine stopped.")
        } else {
            CliLogger.error { "failed to stop engine" }
            echo("Failed to stop engine.")
        }
    }
}

private class EngineRestartCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "restart") {
    override fun run() {
        CliLogger.info { "engine restart" }
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.restart(KlawService.ENGINE)
        if (success) {
            CliLogger.info { "engine restarted" }
            echo("Engine restarted.")
        } else {
            CliLogger.error { "failed to restart engine" }
            echo("Failed to restart engine.")
        }
    }
}
