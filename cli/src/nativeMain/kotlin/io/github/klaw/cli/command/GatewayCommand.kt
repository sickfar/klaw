@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.github.klaw.cli.init.KlawService
import io.github.klaw.cli.init.createServiceManager
import io.github.klaw.cli.util.CliLogger

internal class GatewayCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "gateway") {
    init {
        subcommands(
            GatewayStartCommand(commandRunner, configDir),
            GatewayStopCommand(commandRunner, configDir),
            GatewayRestartCommand(commandRunner, configDir),
        )
    }

    override fun run() = Unit
}

private class GatewayStartCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "start") {
    override fun run() {
        CliLogger.info { "gateway start" }
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.start(KlawService.GATEWAY)
        if (success) {
            CliLogger.info { "gateway started" }
            echo("Gateway started.")
        } else {
            CliLogger.error { "failed to start gateway" }
            echo("Failed to start gateway.")
        }
    }
}

private class GatewayStopCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "stop") {
    override fun run() {
        CliLogger.info { "gateway stop" }
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.stop(KlawService.GATEWAY)
        if (success) {
            CliLogger.info { "gateway stopped" }
            echo("Gateway stopped.")
        } else {
            CliLogger.error { "failed to stop gateway" }
            echo("Failed to stop gateway.")
        }
    }
}

private class GatewayRestartCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "restart") {
    override fun run() {
        CliLogger.info { "gateway restart" }
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.restart(KlawService.GATEWAY)
        if (success) {
            CliLogger.info { "gateway restarted" }
            echo("Gateway restarted.")
        } else {
            CliLogger.error { "failed to restart gateway" }
            echo("Failed to restart gateway.")
        }
    }
}
