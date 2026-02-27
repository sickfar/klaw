@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.github.klaw.cli.init.KlawService
import io.github.klaw.cli.init.ServiceManager

internal class EngineCommand(
    private val commandRunner: (String) -> Int,
) : CliktCommand(name = "engine") {
    init {
        subcommands(
            EngineStartCommand(commandRunner),
            EngineStopCommand(commandRunner),
            EngineRestartCommand(commandRunner),
        )
    }

    override fun run() = Unit
}

private class EngineStartCommand(
    private val commandRunner: (String) -> Int,
) : CliktCommand(name = "start") {
    override fun run() {
        ServiceManager(::echo, commandRunner).start(KlawService.ENGINE)
    }
}

private class EngineStopCommand(
    private val commandRunner: (String) -> Int,
) : CliktCommand(name = "stop") {
    override fun run() {
        ServiceManager(::echo, commandRunner).stop(KlawService.ENGINE)
    }
}

private class EngineRestartCommand(
    private val commandRunner: (String) -> Int,
) : CliktCommand(name = "restart") {
    override fun run() {
        ServiceManager(::echo, commandRunner).restart(KlawService.ENGINE)
    }
}
