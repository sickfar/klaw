package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.AllowedChat
import kotlinx.serialization.SerializationException
import io.github.klaw.common.config.PairingRequest
import io.github.klaw.common.config.encodeGatewayConfig
import io.github.klaw.common.config.klawJson
import io.github.klaw.common.config.klawPrettyJson
import io.github.klaw.common.config.parseGatewayConfig
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal class PairCommand(
    private val configDir: String,
    private val pairingRequestsPath: String,
) : CliktCommand(name = "pair") {
    private val channel by argument(help = "Channel name (e.g. telegram)")
    private val code by argument(help = "Pairing code from /start")

    override fun run() {
        CliLogger.debug { "pair channel=$channel code=$code" }

        val requests = loadRequests()
        val request = requests.find { it.code == code && it.channel == channel }
        if (request == null) {
            echo("Pairing code not found or Invalid for channel $channel.")
            return
        }

        val createdAt = Instant.parse(request.createdAt)
        if (Clock.System.now() - createdAt > EXPIRY) {
            echo("Pairing code expired. Ask user to send /start again.")
            return
        }

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
            } catch (e: SerializationException) {
                echo("Error: could not parse $configPath — ${e::class.simpleName}")
                return
            }

        val updatedConfig = addChatToConfig(config, request)
        writeFileText(configPath, encodeGatewayConfig(updatedConfig))

        val remaining = requests.filter { it.code != code || it.channel != channel }
        saveRequests(remaining)

        CliLogger.info { "Paired chatId=${request.chatId} on channel=${request.channel}" }
        echo("Paired ${request.chatId} on ${request.channel}.")
    }

    private fun addChatToConfig(
        config: io.github.klaw.common.config.GatewayConfig,
        request: PairingRequest,
    ): io.github.klaw.common.config.GatewayConfig {
        val telegram = config.channels.telegram ?: return config
        val existingChat = telegram.allowedChats.find { it.chatId == request.chatId }
        val userId = request.userId
        val updatedChats =
            if (existingChat != null) {
                val userIds =
                    if (userId != null && userId !in existingChat.allowedUserIds) {
                        existingChat.allowedUserIds + userId
                    } else {
                        existingChat.allowedUserIds
                    }
                telegram.allowedChats.map {
                    if (it.chatId == request.chatId) it.copy(allowedUserIds = userIds) else it
                }
            } else {
                val userIds = if (userId != null) listOf(userId) else emptyList()
                telegram.allowedChats + AllowedChat(chatId = request.chatId, allowedUserIds = userIds)
            }
        return config.copy(
            channels =
                config.channels.copy(
                    telegram = telegram.copy(allowedChats = updatedChats),
                ),
        )
    }

    private fun loadRequests(): List<PairingRequest> {
        if (!fileExists(pairingRequestsPath)) return emptyList()
        val text = readFileText(pairingRequestsPath) ?: return emptyList()
        return try {
            klawJson.decodeFromString(ListSerializer(PairingRequest.serializer()), text)
        } catch (e: SerializationException) {
            CliLogger.warn { "Could not parse pairing requests: ${e::class.simpleName}" }
            emptyList()
        }
    }

    private fun saveRequests(requests: List<PairingRequest>) {
        val json = klawPrettyJson.encodeToString(ListSerializer(PairingRequest.serializer()), requests)
        writeFileText(pairingRequestsPath, json)
    }

    companion object {
        private val EXPIRY = 5.minutes
    }
}
