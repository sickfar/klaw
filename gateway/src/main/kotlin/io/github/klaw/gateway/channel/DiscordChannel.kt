package io.github.klaw.gateway.channel

import dev.kord.common.entity.Snowflake
import dev.kord.core.Kord
import dev.kord.core.event.message.MessageCreateEvent
import dev.kord.core.on
import dev.kord.gateway.Intent
import dev.kord.gateway.PrivilegedIntent
import dev.kord.rest.request.RestRequestException
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import jakarta.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Singleton
class DiscordChannel(
    private val config: GatewayConfig,
    private val jsonlWriter: ConversationJsonlWriter,
) : Channel {
    override val name = "discord"

    @Volatile
    private var alive: Boolean = false

    @Volatile
    override var onBecameAlive: (suspend () -> Unit)? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingApprovals = ConcurrentHashMap<String, suspend (Boolean) -> Unit>()
    internal val typingJobs = ConcurrentHashMap<String, Job>()

    internal var buildKordAction: (suspend (token: String, apiBaseUrl: String?) -> Unit)? = null
    internal var selfBotId: String? = null
    internal var typingScope: CoroutineScope = scope
    internal var sendAction: suspend (channelId: String, content: String) -> Unit = { _, _ -> }
    internal var typingAction: suspend (channelId: String) -> Unit = { _ -> }
    internal var sendApprovalAction: suspend (channelId: String, content: String, approvalId: String) -> Unit =
        { _, _, _ -> }
    internal var listenAction: (suspend (onMessage: suspend (IncomingMessage) -> Unit) -> Unit)? = null
    private var customHttpClient: HttpClient? = null

    override fun isAlive(): Boolean = alive

    private suspend fun setAlive(value: Boolean) {
        val wasAlive = alive
        alive = value
        if (value && !wasAlive) {
            onBecameAlive?.invoke()
        }
    }

    override suspend fun start() {
        val discordConfig = config.channels.discord
        if (discordConfig == null) {
            logger.info { "discord config not found, DiscordChannel not started" }
            return
        }
        if (!discordConfig.enabled) {
            logger.debug { "discord channel disabled, skipping start" }
            return
        }
        val token = discordConfig.token
        if (token == null) {
            logger.warn { "discord channel enabled but token is null, skipping start" }
            return
        }

        logger.info { "discord bot starting" }

        val buildAction = buildKordAction
        if (buildAction != null) {
            buildAction(token, discordConfig.apiBaseUrl)
        } else {
            initKord(token, discordConfig.apiBaseUrl)
        }

        setAlive(true)
        logger.info { "discord bot started selfId=$selfBotId" }
    }

    private suspend fun initKord(
        token: String,
        apiBaseUrl: String?,
    ) {
        if (apiBaseUrl != null) {
            initCustom(token, apiBaseUrl)
        } else {
            initKordDefault(token)
        }
    }

    private suspend fun initKordDefault(token: String) {
        val kord = Kord(token)
        selfBotId = kord.selfId.toString()
        sendAction = { channelId, content ->
            kord.rest.channel.createMessage(Snowflake(channelId)) {
                this.content = content
            }
            Unit
        }
        typingAction = { channelId ->
            kord.rest.channel.triggerTypingIndicator(Snowflake(channelId))
        }
        listenAction = { onMessage ->
            kord.on<MessageCreateEvent> {
                val message = this.message
                handleIncomingMessage(
                    channelId = message.channelId.toString(),
                    guildId = message.getGuildOrNull()?.id?.toString(),
                    userId = message.author?.id?.toString(),
                    content = message.content,
                    senderName = message.author?.username,
                    chatTitle =
                        message.channel
                            .asChannel()
                            .data.name.value,
                    platformMessageId = message.id.toString(),
                    onMessage = onMessage,
                )
            }
            @OptIn(PrivilegedIntent::class)
            kord.login {
                intents {
                    +Intent.GuildMessages
                    +Intent.DirectMessages
                    +Intent.MessageContent
                }
            }
        }
    }

    private suspend fun initCustom(
        token: String,
        apiBaseUrl: String,
    ) {
        val httpClient = buildCustomHttpClient()
        customHttpClient = httpClient
        val botUser =
            httpClient
                .get("$apiBaseUrl/api/v10/users/@me") {
                    header("Authorization", "Bot $token")
                }.body<String>()
        val botJson = customJson.parseToJsonElement(botUser).jsonObject
        selfBotId = botJson["id"]?.jsonPrimitive?.content

        val gatewayInfoBody =
            httpClient
                .get("$apiBaseUrl/api/v10/gateway/bot") {
                    header("Authorization", "Bot $token")
                }.body<String>()
        val gatewayJson = customJson.parseToJsonElement(gatewayInfoBody).jsonObject
        val gatewayUrl = gatewayJson["url"]?.jsonPrimitive?.content ?: error("No gateway URL returned")

        sendAction = { channelId, content ->
            httpClient.post("$apiBaseUrl/api/v10/channels/$channelId/messages") {
                header("Authorization", "Bot $token")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"content":${customJson.encodeToString(kotlinx.serialization.serializer<String>(), content)}}""",
                )
            }
            Unit
        }
        typingAction = { channelId ->
            httpClient.post("$apiBaseUrl/api/v10/channels/$channelId/typing") {
                header("Authorization", "Bot $token")
            }
            Unit
        }
        listenAction = { onMessage ->
            val ws = httpClient.webSocketSession(gatewayUrl)
            runCustomGatewayLoop(ws, token, onMessage)
        }
    }

    // Custom gateway loop is used only with apiBaseUrl override (test/mock environments).
    // It does not implement a heartbeat loop because the mock gateway is lenient and does not require one.
    private suspend fun runCustomGatewayLoop(
        ws: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        token: String,
        onMessage: suspend (IncomingMessage) -> Unit,
    ) {
        for (frame in ws.incoming) {
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            val msg = customJson.parseToJsonElement(text).jsonObject
            val op = msg["op"]?.jsonPrimitive?.int ?: continue
            handleCustomGatewayOp(ws, op, msg, token, onMessage)
        }
    }

    private suspend fun handleCustomGatewayOp(
        ws: io.ktor.client.plugins.websocket.DefaultClientWebSocketSession,
        op: Int,
        msg: kotlinx.serialization.json.JsonObject,
        token: String,
        onMessage: suspend (IncomingMessage) -> Unit,
    ) {
        when (op) {
            OP_HELLO -> {
                logger.debug { "custom gateway received HELLO" }
                val escapedToken = customJson.encodeToString(kotlinx.serialization.serializer<String>(), token)
                val identify =
                    """{"op":$OP_IDENTIFY,"d":{"token":$escapedToken,""" +
                        """"intents":33281,"properties":{"os":"linux","browser":"Kord","device":"Kord"}}}"""
                ws.send(identify)
            }

            OP_HEARTBEAT_ACK -> {
                logger.trace { "custom gateway heartbeat ack" }
            }

            OP_DISPATCH -> {
                val eventName = msg["t"]?.jsonPrimitive?.content
                val d = msg["d"]?.jsonObject ?: return
                if (eventName == "MESSAGE_CREATE") {
                    handleCustomMessageCreate(d, onMessage)
                }
            }
        }
    }

    private suspend fun handleCustomMessageCreate(
        d: kotlinx.serialization.json.JsonObject,
        onMessage: suspend (IncomingMessage) -> Unit,
    ) {
        val parsed = parseDispatchFields(d) ?: return

        handleIncomingMessage(
            channelId = parsed.channelId,
            guildId = parsed.guildId,
            userId = parsed.userId,
            content = parsed.content,
            senderName = parsed.username,
            chatTitle = null,
            platformMessageId = parsed.messageId,
            threadType = parsed.threadType,
            onMessage = onMessage,
        )
    }

    private fun parseDispatchFields(d: kotlinx.serialization.json.JsonObject): DispatchFields? {
        val channelId = d["channel_id"]?.jsonPrimitive?.content ?: return null
        val content = d["content"]?.jsonPrimitive?.content ?: return null
        val author = d["author"]?.jsonObject
        val threadType =
            d["thread"]
                ?.jsonObject
                ?.get("type")
                ?.jsonPrimitive
                ?.int
        return DispatchFields(
            channelId = channelId,
            guildId = d["guild_id"]?.jsonPrimitive?.content,
            userId = author?.get("id")?.jsonPrimitive?.content,
            username = author?.get("username")?.jsonPrimitive?.content,
            content = content,
            messageId = d["id"]?.jsonPrimitive?.content,
            threadType = threadType,
        )
    }

    private data class DispatchFields(
        val channelId: String,
        val guildId: String?,
        val userId: String?,
        val username: String?,
        val content: String,
        val messageId: String?,
        val threadType: Int? = null,
    )

    companion object {
        private const val TYPING_REFRESH_INTERVAL_MS = 8_000L
        private const val THREAD_TYPE_FORUM = 15
        private const val OP_DISPATCH = 0
        private const val OP_IDENTIFY = 2
        private const val OP_HELLO = 10
        private const val OP_HEARTBEAT_ACK = 11
        private val customJson = Json { ignoreUnknownKeys = true }

        internal fun buildCustomHttpClient(): HttpClient =
            HttpClient(CIO) {
                install(WebSockets)
            }
    }

    override suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit) {
        val action = listenAction ?: return
        logger.debug { "discord channel starting event listener" }
        action(onMessage)
    }

    @Suppress("LongParameterList")
    internal suspend fun handleIncomingMessage(
        channelId: String,
        guildId: String?,
        userId: String?,
        content: String,
        senderName: String?,
        chatTitle: String? = null,
        platformMessageId: String? = null,
        threadType: Int? = null,
        onMessage: suspend (IncomingMessage) -> Unit,
    ) {
        logger.trace {
            "discord message received guildId=$guildId channelId=$channelId userId=$userId len=${content.length}"
        }

        if (userId == selfBotId) {
            logger.trace {
                "discord message rejected channelId=$channelId userId=$userId reason=bot_self_message"
            }
            return
        }

        if (!isGuildAllowed(guildId, channelId, userId)) {
            return
        }

        val chatType = determineChatType(guildId, threadType)

        val channelIdULong =
            channelId.toULongOrNull() ?: run {
                logger.warn { "discord message rejected channelId=$channelId reason=invalid_channel_id" }
                return
            }

        val incoming =
            DiscordNormalizer.normalize(
                channelId = channelIdULong,
                text = content,
                userId = userId?.toULongOrNull(),
                senderName = senderName,
                chatType = chatType,
                chatTitle = chatTitle,
                platformMessageId = platformMessageId,
                guildId = guildId,
            )

        logger.trace { "discord message normalized chatId=${incoming.chatId} chatType=$chatType" }

        startTyping(channelId)

        jsonlWriter.writeInbound(incoming)

        logger.debug { "discord message forwarded chatId=${incoming.chatId}" }
        onMessage(incoming)
    }

    private fun isGuildAllowed(
        guildId: String?,
        channelId: String,
        userId: String?,
    ): Boolean {
        val discordConfig = config.channels.discord ?: return false
        val guilds = discordConfig.allowedGuilds

        if (guildId == null) {
            val allowed = guilds.any { g -> userId != null && userId in g.allowedUserIds }
            if (!allowed) {
                logger.trace {
                    "discord message rejected channelId=$channelId userId=$userId reason=dm_user_not_allowed"
                }
            }
            return allowed
        }

        val guild = guilds.find { it.guildId == guildId }
        if (guild == null) {
            logger.trace {
                "discord message rejected guildId=$guildId channelId=$channelId userId=$userId reason=unknown_guild"
            }
            return false
        }

        if (guild.allowedChannelIds.isNotEmpty() && channelId !in guild.allowedChannelIds) {
            logger.trace {
                "discord message rejected guildId=$guildId channelId=$channelId userId=$userId reason=disallowed_channel"
            }
            return false
        }

        if (guild.allowedUserIds.isEmpty() || userId == null || userId !in guild.allowedUserIds) {
            logger.trace {
                "discord message rejected guildId=$guildId channelId=$channelId userId=$userId reason=disallowed_user"
            }
            return false
        }

        logger.trace { "guild allowlist check guildId=$guildId result=true" }
        return true
    }

    private fun determineChatType(
        guildId: String?,
        threadType: Int? = null,
    ): String =
        when {
            guildId == null -> "dm"
            threadType == THREAD_TYPE_FORUM -> "guild_forum"
            threadType != null -> "guild_thread"
            else -> "guild_text"
        }

    override suspend fun send(
        chatId: String,
        response: OutgoingMessage,
    ) {
        val channelId = chatId.removePrefix("discord_")
        stopTyping(channelId)
        if (!alive) {
            logger.warn { "DiscordChannel.send called but channel not alive" }
            return
        }
        val chunks = splitMessage(response.content, DiscordNormalizer.DISCORD_MAX_MESSAGE_LENGTH)

        logger.trace { "discord send attempt channelId=$channelId len=${response.content.length}" }

        for (chunk in chunks) {
            runCatching {
                withSendRetry {
                    sendAction(channelId, chunk)
                }
            }.onSuccess {
                setAlive(true)
            }.onFailure { e ->
                alive = false
                logger.error(e) { "Failed to send Discord message to chatId=$chatId" }
                return
            }
        }

        jsonlWriter.writeOutbound(chatId, response.content)

        logger.debug { "discord message sent channelId=$channelId chunks=${chunks.size}" }
    }

    override suspend fun sendApproval(
        chatId: String,
        request: ApprovalRequestMessage,
        onResult: suspend (Boolean) -> Unit,
    ) {
        val channelId = chatId.removePrefix("discord_")
        stopTyping(channelId)
        if (!alive) {
            logger.warn { "DiscordChannel.sendApproval called but channel not alive" }
            return
        }
        pendingApprovals[request.id] = onResult

        logger.trace { "discord approval button sent approvalId=${request.id} channelId=$channelId" }

        runCatching {
            withSendRetry {
                sendApprovalAction(
                    channelId,
                    "Command approval requested:\n\n${request.command}\n\nRisk score: ${request.riskScore}/10",
                    request.id,
                )
            }
        }.onFailure { e ->
            pendingApprovals.remove(request.id)
            logger.error(e) { "Failed to send approval request to chatId=$chatId" }
        }
    }

    internal suspend fun handleApprovalResponse(
        approvalId: String,
        approved: Boolean,
    ) {
        val callback = pendingApprovals.remove(approvalId)
        if (callback != null) {
            logger.trace { "discord approval received approvalId=$approvalId approved=$approved" }
            callback(approved)
        } else {
            logger.debug { "No pending approval for id=$approvalId" }
        }
    }

    internal fun startTyping(channelId: String) {
        typingJobs.compute(channelId) { _, existingJob ->
            existingJob?.cancel()
            typingScope.launch {
                while (isActive) {
                    try {
                        typingAction(channelId)
                        logger.trace { "discord typing triggered channelId=$channelId" }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: RestRequestException) {
                        logger.warn { "discord typing failed channelId=$channelId (${e::class.simpleName})" }
                    } catch (e: IOException) {
                        logger.warn { "discord typing failed channelId=$channelId (${e::class.simpleName})" }
                    }
                    delay(TYPING_REFRESH_INTERVAL_MS)
                }
            }
        }
        logger.trace { "typing started for channelId=$channelId" }
    }

    internal fun stopTyping(channelId: String) {
        val cancelled = typingJobs.remove(channelId)?.cancel()
        if (cancelled != null) {
            logger.trace { "typing stopped for channelId=$channelId" }
        }
    }

    override suspend fun stop() {
        alive = false
        typingJobs.values.forEach { it.cancel() }
        typingJobs.clear()
        customHttpClient?.close()
        customHttpClient = null
        logger.info { "DiscordChannel stopped" }
    }
}
