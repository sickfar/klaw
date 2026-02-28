@file:OptIn(kotlin.experimental.ExperimentalNativeApi::class)

package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import io.github.klaw.cli.init.createServiceManager
import io.github.klaw.cli.util.CliLogger

internal class StopCommand(
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : CliktCommand(name = "stop") {
    override fun run() {
        CliLogger.info { "stopping all services" }
        val manager = createServiceManager(::echo, commandRunner, configDir)
        val success = manager.stopAll()
        if (success) {
            CliLogger.info { "all services stopped" }
            echo("All services stopped.")
        } else {
            CliLogger.error { "failed to stop some services" }
            echo("Failed to stop some services.")
        }
    }
}
