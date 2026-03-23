package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.github.klaw.cli.EngineRequest

internal class ChannelsCommand(requestFn: EngineRequest) : CliktCommand(
    name = "channels",
    help = "Manage messaging channels (Telegram, WhatsApp, etc.)"
) {
    init {
        subcommands(
            ChannelsAddCommand(requestFn),
            ChannelsRemoveCommand(requestFn),
            ChannelsListCommand(requestFn)
        )
    }

    override fun run() = Unit
}

internal class ChannelsAddCommand(private val requestFn: EngineRequest) : CliktCommand(
    name = "add",
    help = "Add a messaging channel"
) {
    private val channel by option("--channel", help = "Channel type (telegram, whatsapp, etc.)").required()
    private val token by option("--token", help = "API token or credential")

    override fun run() {
        val params = mutableMapOf("type" to channel)
        token?.let { params["token"] = it }
        val response = requestFn("channels.add", params)
        echo(response)
    }
}

internal class ChannelsRemoveCommand(private val requestFn: EngineRequest) : CliktCommand(
    name = "remove",
    help = "Remove a messaging channel"
) {
    private val channel by option("--channel", help = "Channel type to remove").required()

    override fun run() {
        val response = requestFn("channels.remove", mapOf("type" to channel))
        echo(response)
    }
}

internal class ChannelsListCommand(private val requestFn: EngineRequest) : CliktCommand(
    name = "list",
    help = "List configured messaging channels"
) {
    override fun run() {
        val response = requestFn("channels.list", emptyMap())
        echo(response)
    }
}
