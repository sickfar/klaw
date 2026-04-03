package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger

internal class ContextCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "context") {
    private val chatId by option("--chat-id", help = "Chat ID to diagnose (default: most recent session)")
    private val json by option("--json", help = "Output as JSON").flag()
    private val agent by option("--agent", "-a", help = "Agent ID").default("default")

    override fun run() {
        val params =
            buildMap {
                chatId?.let { put("chat_id", it) }
                if (json) put("json", "true")
            }
        CliLogger.debug { "requesting context_diagnose params=$params" }
        try {
            val response = requestFn("context_diagnose", params, agent)
            echo(response.replace("\\n", "\n"))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
        }
    }
}
