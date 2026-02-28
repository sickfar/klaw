@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import io.github.klaw.cli.init.createServiceManager

internal class StopCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "stop") {
    override fun run() {
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.stopAll()
        if (success) {
            echo("All services stopped.")
        } else {
            echo("Failed to stop some services.")
        }
    }
}
