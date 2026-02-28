@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.github.klaw.cli.init.KlawService
import io.github.klaw.cli.init.createServiceManager

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
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.start(KlawService.ENGINE)
        if (success) {
            echo("Engine started.")
        } else {
            echo("Failed to start engine.")
        }
    }
}

private class EngineStopCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "stop") {
    override fun run() {
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.stop(KlawService.ENGINE)
        if (success) {
            echo("Engine stopped.")
        } else {
            echo("Failed to stop engine.")
        }
    }
}

private class EngineRestartCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "restart") {
    override fun run() {
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.restart(KlawService.ENGINE)
        if (success) {
            echo("Engine restarted.")
        } else {
            echo("Failed to restart engine.")
        }
    }
}
