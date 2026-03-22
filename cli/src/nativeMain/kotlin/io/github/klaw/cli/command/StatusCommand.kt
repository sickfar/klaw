package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger

internal class StatusCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "status") {
    private val deep by option("--deep", help = "Deep health probe").flag()
    private val json by option("--json", help = "Output as JSON").flag()
    private val usage by option("--usage", help = "Show LLM usage statistics").flag()
    private val timeout by option("--timeout", help = "Probe timeout in ms").int()
    private val all by option("--all", help = "Full diagnosis (--deep + --usage)").flag()

    override fun run() {
        val params =
            buildMap {
                if (deep) put("deep", "true")
                if (json) put("json", "true")
                if (usage) put("usage", "true")
                if (all) put("all", "true")
                timeout?.let { put("timeout", it.toString()) }
            }
        CliLogger.debug { "requesting status params=$params" }
        try {
            val response = requestFn("status", params)
            echo(response.replace("\\n", "\n"))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}
