package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.klaw.cli.init.KlawService
import io.github.klaw.cli.init.createServiceManager
import io.github.klaw.cli.util.CliLogger

internal class ServiceCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "service") {
    init {
        subcommands(
            ServiceStartCommand(commandRunner, configDir),
            ServiceStopCommand(commandRunner, configDir),
            ServiceRestartCommand(commandRunner, configDir),
        )
    }

    override fun run() = Unit
}

private class ServiceStartCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "start") {
    val target by argument(help = "Service target: engine, gateway, or all")

    override fun run() {
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success =
            when (target) {
                "engine" -> {
                    CliLogger.info { "starting engine" }
                    manager.start(KlawService.ENGINE)
                }

                "gateway" -> {
                    CliLogger.info { "starting gateway" }
                    manager.start(KlawService.GATEWAY)
                }

                "all" -> {
                    CliLogger.info { "starting all services" }
                    manager.startAll()
                }

                else -> {
                    echo("Unknown target: $target (use 'engine', 'gateway', or 'all')")
                    return
                }
            }
        if (success) {
            val label = if (target == "all") "All services" else target.replaceFirstChar { it.uppercase() }
            echo("$label started.")
        } else {
            val label = if (target == "all") "all services" else target
            echo("Failed to start $label.")
        }
    }
}

private class ServiceStopCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "stop") {
    val target by argument(help = "Service target: engine, gateway, or all")

    override fun run() {
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success =
            when (target) {
                "engine" -> {
                    CliLogger.info { "stopping engine" }
                    manager.stop(KlawService.ENGINE)
                }

                "gateway" -> {
                    CliLogger.info { "stopping gateway" }
                    manager.stop(KlawService.GATEWAY)
                }

                "all" -> {
                    CliLogger.info { "stopping all services" }
                    manager.stopAll()
                }

                else -> {
                    echo("Unknown target: $target (use 'engine', 'gateway', or 'all')")
                    return
                }
            }
        if (success) {
            val label = if (target == "all") "All services" else target.replaceFirstChar { it.uppercase() }
            echo("$label stopped.")
        } else {
            val label = if (target == "all") "all services" else target
            echo("Failed to stop $label.")
        }
    }
}

private class ServiceRestartCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "restart") {
    val target by argument(help = "Service target: engine, gateway, or all")

    override fun run() {
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success =
            when (target) {
                "engine" -> {
                    CliLogger.info { "restarting engine" }
                    manager.restart(KlawService.ENGINE)
                }

                "gateway" -> {
                    CliLogger.info { "restarting gateway" }
                    manager.restart(KlawService.GATEWAY)
                }

                "all" -> {
                    CliLogger.info { "restarting all services" }
                    manager.restartAll()
                }

                else -> {
                    echo("Unknown target: $target (use 'engine', 'gateway', or 'all')")
                    return
                }
            }
        if (success) {
            val label = if (target == "all") "All services" else target.replaceFirstChar { it.uppercase() }
            echo("$label restarted.")
        } else {
            val label = if (target == "all") "all services" else target
            echo("Failed to restart $label.")
        }
    }
}
