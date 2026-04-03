package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.AllowedGuild
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.PairingRequest
import io.github.klaw.common.config.encodeGatewayConfig
import io.github.klaw.common.config.klawJson
import io.github.klaw.common.config.klawPrettyJson
import io.github.klaw.common.config.parseGatewayConfig
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

internal class ChannelsCommand(
    configDir: String,
    pairingRequestsPath: String,
    requestFn: EngineRequest,
) : CliktCommand(name = "channels") {
    init {
        subcommands(
            ChannelsListCommand(configDir),
            ChannelsStatusCommand(configDir, requestFn),
            ChannelsPairCommand(configDir, pairingRequestsPath),
            ChannelsUnpairCommand(configDir),
        )
    }

    override fun run() = Unit
}

internal class ChannelsListCommand(
    private val configDir: String,
) : CliktCommand(name = "list") {
    private val json by option("--json", help = "Output as JSON").flag()

    override fun run() {
        CliLogger.debug { "channels list" }
        val config =
            when (val result = loadGatewayConfig(configDir)) {
                is ConfigResult.Ok -> {
                    result.config
                }

                is ConfigResult.Error -> {
                    echo(result.message)
                    return
                }
            }

        val channels = collectChannels(config)
        if (channels.isEmpty()) {
            echo("No channels configured.")
            return
        }

        if (json) {
            echoJson(channels)
        } else {
            echoText(channels)
        }
    }

    private fun echoText(channels: List<ChannelInfo>) {
        channels.forEach { ch ->
            val details =
                buildList {
                    if (ch.enabled != null) add(if (ch.enabled) "enabled" else "disabled")
                    if (ch.port != null) add("port ${ch.port}")
                    if (ch.paired != null) add("${ch.paired} paired")
                }
            echo("  ${ch.name}: ${details.joinToString(", ")}")
        }
    }

    private fun echoJson(channels: List<ChannelInfo>) {
        val items =
            channels.joinToString(",") { ch ->
                buildString {
                    append("{\"name\":\"${ch.name}\"")
                    if (ch.enabled != null) append(",\"enabled\":${ch.enabled}")
                    if (ch.port != null) append(",\"port\":${ch.port}")
                    if (ch.paired != null) append(",\"paired\":${ch.paired}")
                    append("}")
                }
            }
        echo("{\"channels\":[$items]}")
    }
}

internal class ChannelsStatusCommand(
    private val configDir: String,
    private val requestFn: EngineRequest,
) : CliktCommand(name = "status") {
    private val probe by option("--probe", help = "Test gateway connectivity via Engine").flag()
    private val json by option("--json", help = "Output as JSON").flag()

    override fun run() {
        CliLogger.debug { "channels status probe=$probe" }
        val config =
            when (val result = loadGatewayConfig(configDir)) {
                is ConfigResult.Ok -> {
                    result.config
                }

                is ConfigResult.Error -> {
                    echo(result.message)
                    return
                }
            }

        val channels = collectChannels(config)
        var gatewayStatus: String? = null

        if (probe) {
            gatewayStatus = probeGateway()
        }

        if (json) {
            echoJson(channels, gatewayStatus)
        } else {
            echoText(channels, gatewayStatus)
        }
    }

    private fun probeGateway(): String? =
        try {
            val response = requestFn("status", mapOf("deep" to "true"))
            extractGatewayStatus(response)
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: klaw service start engine")
            null
        }

    private fun extractGatewayStatus(response: String): String {
        val idx = response.indexOf("gateway_status")
        if (idx < 0) return "unknown"
        val valueStart = response.indexOf(':', idx) + 1
        if (valueStart <= 0) return "unknown"
        val valueEnd = response.indexOfAny(charArrayOf(',', '}'), valueStart)
        if (valueEnd < 0) return "unknown"
        return response.substring(valueStart, valueEnd).trim().trim('"')
    }

    private fun echoText(
        channels: List<ChannelInfo>,
        gatewayStatus: String?,
    ) {
        if (channels.isEmpty()) {
            echo("No channels configured.")
        } else {
            channels.forEach { ch ->
                val details =
                    buildList {
                        if (ch.enabled != null) add(if (ch.enabled) "enabled" else "disabled")
                        if (ch.port != null) add("port ${ch.port}")
                        if (ch.paired != null) add("${ch.paired} paired")
                    }
                echo("  ${ch.name}: ${details.joinToString(", ")}")
            }
        }
        if (gatewayStatus != null) {
            echo("  gateway: $gatewayStatus")
        }
    }

    private fun echoJson(
        channels: List<ChannelInfo>,
        gatewayStatus: String?,
    ) {
        val items =
            channels.joinToString(",") { ch ->
                buildString {
                    append("{\"name\":\"${ch.name}\"")
                    if (ch.enabled != null) append(",\"enabled\":${ch.enabled}")
                    if (ch.port != null) append(",\"port\":${ch.port}")
                    if (ch.paired != null) append(",\"paired\":${ch.paired}")
                    append("}")
                }
            }
        val gwPart = if (gatewayStatus != null) ",\"gatewayStatus\":\"$gatewayStatus\"" else ""
        echo("{\"channels\":[$items]$gwPart}")
    }
}

internal class ChannelsPairCommand(
    private val configDir: String,
    private val pairingRequestsPath: String,
) : CliktCommand(name = "pair") {
    private val channel by argument(help = "Channel name (e.g. telegram)")
    private val code by argument(help = "Pairing code from /start")

    override fun run() {
        CliLogger.debug { "channels pair channel=$channel" }

        val requests = loadPairingRequests(pairingRequestsPath)
        val request = requests.find { it.code == code && it.channel == channel }
        if (request == null) {
            echo("Pairing code not found or invalid for channel $channel.")
            return
        }

        val createdAt = Instant.parse(request.createdAt)
        if (Clock.System.now() - createdAt > EXPIRY) {
            echo("Pairing code expired. Ask user to send /start again.")
            return
        }

        val configPath = "$configDir/gateway.json"
        val config =
            when (val result = loadGatewayConfig(configDir)) {
                is ConfigResult.Ok -> {
                    result.config
                }

                is ConfigResult.Error -> {
                    echo(result.message)
                    return
                }
            }

        val updatedConfig =
            when (channel) {
                "discord" -> addGuildToConfig(config, request)
                else -> addChatToConfig(config, request)
            }
        writeFileText(configPath, encodeGatewayConfig(updatedConfig))

        val remaining = requests.filter { it.code != code || it.channel != channel }
        savePairingRequests(pairingRequestsPath, remaining)

        CliLogger.info { "Paired chatId=${request.chatId} on channel=${request.channel}" }
        echo("Paired ${request.chatId} on ${request.channel}.")
    }

    private fun addChatToConfig(
        config: GatewayConfig,
        request: PairingRequest,
    ): GatewayConfig {
        val telegramEntry =
            config.channels.telegram.entries
                .firstOrNull() ?: return config
        val (channelName, telegram) = telegramEntry
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
                    telegram =
                        config.channels.telegram +
                            mapOf(channelName to telegram.copy(allowedChats = updatedChats)),
                ),
        )
    }

    private fun addGuildToConfig(
        config: GatewayConfig,
        request: PairingRequest,
    ): GatewayConfig {
        val discordEntry =
            config.channels.discord.entries
                .firstOrNull() ?: return config
        val (channelName, discord) = discordEntry
        val guildId = request.guildId ?: return config
        val userId = request.userId
        val existingGuild = discord.allowedGuilds.find { it.guildId == guildId }
        val updatedGuilds =
            if (existingGuild != null) {
                val userIds =
                    if (userId != null && userId !in existingGuild.allowedUserIds) {
                        existingGuild.allowedUserIds + userId
                    } else {
                        existingGuild.allowedUserIds
                    }
                discord.allowedGuilds.map {
                    if (it.guildId == guildId) it.copy(allowedUserIds = userIds) else it
                }
            } else {
                val userIds = if (userId != null) listOf(userId) else emptyList()
                discord.allowedGuilds + AllowedGuild(guildId = guildId, allowedUserIds = userIds)
            }
        return config.copy(
            channels =
                config.channels.copy(
                    discord =
                        config.channels.discord +
                            mapOf(channelName to discord.copy(allowedGuilds = updatedGuilds)),
                ),
        )
    }

    companion object {
        private val EXPIRY = 5.minutes
    }
}

internal class ChannelsUnpairCommand(
    private val configDir: String,
) : CliktCommand(name = "unpair") {
    private val channel by argument(help = "Channel name (e.g. telegram, discord)")
    private val chatId by argument(help = "Chat ID or guild ID to unpair")

    override fun run() {
        CliLogger.debug { "channels unpair channel=$channel chatId=$chatId" }

        val configPath = "$configDir/gateway.json"
        val config =
            when (val result = loadGatewayConfig(configDir)) {
                is ConfigResult.Ok -> {
                    result.config
                }

                is ConfigResult.Error -> {
                    echo(result.message)
                    return
                }
            }

        val updatedConfig =
            when (channel) {
                "discord" -> unpairDiscord(config) ?: return
                else -> unpairTelegram(config) ?: return
            }
        writeFileText(configPath, encodeGatewayConfig(updatedConfig))
        CliLogger.info { "Unpaired chatId=$chatId from channel=$channel" }
        echo("Unpaired $chatId from $channel.")
    }

    private fun unpairTelegram(config: GatewayConfig): GatewayConfig? {
        val telegramEntry =
            config.channels.telegram.entries
                .firstOrNull()
        if (telegramEntry == null) {
            echo("No telegram config found.")
            return null
        }
        val (channelName, telegram) = telegramEntry
        val existing = telegram.allowedChats.find { it.chatId == chatId }
        if (existing == null) {
            echo("Chat $chatId not found in allowedChats.")
            return null
        }
        val updatedChats = telegram.allowedChats.filter { it.chatId != chatId }
        return config.copy(
            channels =
                config.channels.copy(
                    telegram =
                        config.channels.telegram +
                            mapOf(channelName to telegram.copy(allowedChats = updatedChats)),
                ),
        )
    }

    private fun unpairDiscord(config: GatewayConfig): GatewayConfig? {
        val discordEntry =
            config.channels.discord.entries
                .firstOrNull()
        if (discordEntry == null) {
            echo("No discord config found.")
            return null
        }
        val (channelName, discord) = discordEntry
        val existing = discord.allowedGuilds.find { it.guildId == chatId }
        if (existing == null) {
            echo("Guild $chatId not found in allowedGuilds.")
            return null
        }
        val updatedGuilds = discord.allowedGuilds.filter { it.guildId != chatId }
        return config.copy(
            channels =
                config.channels.copy(
                    discord =
                        config.channels.discord +
                            mapOf(channelName to discord.copy(allowedGuilds = updatedGuilds)),
                ),
        )
    }
}

private data class ChannelInfo(
    val name: String,
    val enabled: Boolean? = null,
    val port: Int? = null,
    val paired: Int? = null,
)

private sealed class ConfigResult {
    data class Ok(
        val config: GatewayConfig,
    ) : ConfigResult()

    data class Error(
        val message: String,
    ) : ConfigResult()
}

private fun loadGatewayConfig(configDir: String): ConfigResult {
    val configPath = "$configDir/gateway.json"
    if (!fileExists(configPath)) {
        return ConfigResult.Error("gateway.json not found at $configPath — run: klaw init")
    }
    val content =
        readFileText(configPath)
            ?: return ConfigResult.Error("Error: could not read $configPath")
    return try {
        ConfigResult.Ok(parseGatewayConfig(content))
    } catch (e: SerializationException) {
        ConfigResult.Error("Error: could not parse $configPath — ${e::class.simpleName}")
    }
}

private fun collectChannels(config: GatewayConfig): List<ChannelInfo> =
    buildList {
        config.channels.telegram.forEach { (name, tg) ->
            add(ChannelInfo(name = "telegram[$name]", enabled = true, paired = tg.allowedChats.size))
        }
        config.channels.discord.forEach { (name, dc) ->
            add(ChannelInfo(name = "discord[$name]", enabled = true, paired = dc.allowedGuilds.size))
        }
        config.channels.websocket.forEach { (name, ws) ->
            add(ChannelInfo(name = "local_ws[$name]", enabled = true, port = ws.port))
        }
    }

private fun loadPairingRequests(path: String): List<PairingRequest> {
    if (!fileExists(path)) return emptyList()
    val text = readFileText(path) ?: return emptyList()
    return try {
        klawJson.decodeFromString(ListSerializer(PairingRequest.serializer()), text)
    } catch (e: SerializationException) {
        CliLogger.warn { "Could not parse pairing requests: ${e::class.simpleName}" }
        emptyList()
    }
}

private fun savePairingRequests(
    path: String,
    requests: List<PairingRequest>,
) {
    val json = klawPrettyJson.encodeToString(ListSerializer(PairingRequest.serializer()), requests)
    writeFileText(path, json)
}
