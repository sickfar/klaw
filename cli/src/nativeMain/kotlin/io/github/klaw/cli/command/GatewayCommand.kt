@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.github.klaw.cli.init.KlawService
import io.github.klaw.cli.init.ServiceManager

internal class GatewayCommand(
    private val commandRunner: (String) -> Int,
) : CliktCommand(name = "gateway") {
    init {
        subcommands(
            GatewayStartCommand(commandRunner),
            GatewayStopCommand(commandRunner),
            GatewayRestartCommand(commandRunner),
        )
    }

    override fun run() = Unit
}

private class GatewayStartCommand(
    private val commandRunner: (String) -> Int,
) : CliktCommand(name = "start") {
    override fun run() {
        ServiceManager(::echo, commandRunner).start(KlawService.GATEWAY)
    }
}

private class GatewayStopCommand(
    private val commandRunner: (String) -> Int,
) : CliktCommand(name = "stop") {
    override fun run() {
        ServiceManager(::echo, commandRunner).stop(KlawService.GATEWAY)
    }
}

private class GatewayRestartCommand(
    private val commandRunner: (String) -> Int,
) : CliktCommand(name = "restart") {
    override fun run() {
        ServiceManager(::echo, commandRunner).restart(KlawService.GATEWAY)
    }
}
