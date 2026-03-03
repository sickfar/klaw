package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.encodeGatewayConfig
import io.github.klaw.common.config.parseGatewayConfig

internal class UnpairCommand(
    private val configDir: String,
) : CliktCommand(name = "unpair") {
    private val channel by argument(help = "Channel name (e.g. telegram)")
    private val chatId by argument(help = "Chat ID to unpair")

    override fun run() {
        CliLogger.debug { "unpair channel=$channel chatId=$chatId" }

        val configPath = "$configDir/gateway.json"
        if (!fileExists(configPath)) {
            echo("gateway.json not found at $configPath — run: klaw init")
            return
        }
        val configContent =
            readFileText(configPath) ?: run {
                echo("Error: could not read $configPath")
                return
            }
        val config =
            try {
                parseGatewayConfig(configContent)
            } catch (e: Exception) {
                echo("Error: could not parse $configPath — ${e::class.simpleName}")
                return
            }

        val telegram = config.channels.telegram
        if (telegram == null) {
            echo("No telegram config found.")
            return
        }
        val existing = telegram.allowedChats.find { it.chatId == chatId }
        if (existing == null) {
            echo("Chat $chatId not found in allowedChats.")
            return
        }

        val updatedChats = telegram.allowedChats.filter { it.chatId != chatId }
        val updatedConfig =
            config.copy(
                channels =
                    config.channels.copy(
                        telegram = telegram.copy(allowedChats = updatedChats),
                    ),
            )
        writeFileText(configPath, encodeGatewayConfig(updatedConfig))
        CliLogger.info { "Unpaired chatId=$chatId from channel=$channel" }
        echo("Unpaired $chatId from $channel.")
    }
}
