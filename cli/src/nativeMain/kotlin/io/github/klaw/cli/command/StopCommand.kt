@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import io.github.klaw.cli.init.ServiceManager

internal class StopCommand(
    private val commandRunner: (String) -> Int,
) : CliktCommand(name = "stop") {
    override fun run() {
        ServiceManager(::echo, commandRunner).stopAll()
    }
}
