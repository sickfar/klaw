@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.github.klaw.cli.init.KlawService
import io.github.klaw.cli.init.createServiceManager

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
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.start(KlawService.GATEWAY)
        if (success) {
            echo("Gateway started.")
        } else {
            echo("Failed to start gateway.")
        }
    }
}

private class GatewayStopCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "stop") {
    override fun run() {
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.stop(KlawService.GATEWAY)
        if (success) {
            echo("Gateway stopped.")
        } else {
            echo("Failed to stop gateway.")
        }
    }
}

private class GatewayRestartCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "restart") {
    override fun run() {
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.restart(KlawService.GATEWAY)
        if (success) {
            echo("Gateway restarted.")
        } else {
            echo("Failed to restart gateway.")
        }
    }
}
