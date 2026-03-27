package io.github.klaw.gateway.channel

import dev.inmo.kslog.common.filter.filtered
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.CommonBotException
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.answers.answerCallbackQuery
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.extensions.api.edit.reply_markup.editMessageReplyMarkup
import dev.inmo.tgbotapi.extensions.api.files.downloadFile
import dev.inmo.tgbotapi.extensions.api.get.getFileAdditionalInfo
import dev.inmo.tgbotapi.extensions.api.send.sendActionTyping
import dev.inmo.tgbotapi.extensions.api.send.sendMessageDraft
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onPhoto
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.CallbackQueryId
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.DraftId
import dev.inmo.tgbotapi.types.MessageId
import dev.inmo.tgbotapi.types.RawChatId
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardButtons.CallbackDataInlineKeyboardButton
import dev.inmo.tgbotapi.types.buttons.InlineKeyboardMarkup
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.protocol.ApprovalRequestMessage
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.plugins.HttpRequestTimeoutException
import jakarta.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Singleton
class TelegramChannel(
    private val config: GatewayConfig,
    private val jsonlWriter: ConversationJsonlWriter,
) : Channel {
    override val name = "telegram"

    @Volatile private var alive: Boolean = false
    override var onBecameAlive: (suspend () -> Unit)? = null
    private var bot: TelegramBot? = null
    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    internal val pendingApprovals = ConcurrentHashMap<String, suspend (Boolean) -> Unit>()
    internal val approvalMessageIds = ConcurrentHashMap<String, Pair<Long, Long>>()
    internal val typingJobs = ConcurrentHashMap<String, Job>()
    internal val chatTypes = ConcurrentHashMap<String, String>()
    internal val streamStates = ConcurrentHashMap<String, TelegramStreamState>()
    internal var typingAction: suspend (Long) -> Unit = { platformId ->
        bot?.sendActionTyping(ChatId(RawChatId(platformId)))
    }
    internal var typingScope: CoroutineScope = scope
    internal var listenScope: CoroutineScope = scope
    internal var pollOnce: (suspend (suspend (IncomingMessage) -> Unit) -> Unit)? = null
    internal var sendAction: (suspend (Long, String) -> Unit)? = null
    internal var sendApprovalAction: (suspend (Long, String, InlineKeyboardMarkup) -> Long)? = null
    internal var answerCallbackAction: (suspend (String, String?) -> Unit)? = null
    internal var editMessageAction: (suspend (Long, Long) -> Unit)? = null
    internal var draftAction: (suspend (Long, Long, String) -> Unit)? = null

    override fun isAlive(): Boolean = alive

    private suspend fun setAlive(value: Boolean) {
        val wasAlive = alive
        alive = value
        if (value && !wasAlive) {
            onBecameAlive?.invoke()
        }
    }

    override suspend fun start() {
        val telegramConfig =
            config.channels.telegram ?: run {
                logger.info { "Telegram config not found, TelegramChannel not started" }
                return
            }
        logger.info { "Starting TelegramChannel" }
        val b =
            if (telegramConfig.apiBaseUrl != null) {
                telegramBot(telegramConfig.token, telegramConfig.apiBaseUrl!!) {
                    logger =
                        logger.filtered { _, _, throwable ->
                            throwable !is HttpRequestTimeoutException &&
                                throwable?.cause !is HttpRequestTimeoutException
                        }
                }
            } else {
                telegramBot(telegramConfig.token) {
                    logger =
                        logger.filtered { _, _, throwable ->
                            throwable !is HttpRequestTimeoutException &&
                                throwable?.cause !is HttpRequestTimeoutException
                        }
                }
            }
        bot = b
        if (draftAction == null) {
            draftAction = { platformId, draftId, text ->
                b.sendMessageDraft(ChatId(RawChatId(platformId)), DraftId(draftId), text)
            }
        }
        setAlive(true)
        if (config.commands.isNotEmpty()) {
            runCatching {
                b.setMyCommands(config.commands.map { BotCommand(it.name, it.description) })
                logger.debug { "Registered ${config.commands.size} bot commands with Telegram" }
            }.onFailure { e ->
                logger.warn(e) { "Failed to register bot commands" }
            }
        }
    }

    override suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit) {
        val b = bot
        if (b == null && pollOnce == null) return
        logger.debug { "TelegramChannel starting long polling" }
        var backoff = INITIAL_POLL_BACKOFF_MS
        while (listenScope.isActive) {
            val startNanos = System.nanoTime()
            try {
                val poll = pollOnce
                if (poll != null) {
                    poll(onMessage)
                } else {
                    runLongPolling(b!!, onMessage)
                }
                logger.warn { "Telegram long polling ended, restarting in ${backoff}ms" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                logger.warn { "Telegram long polling failed (${e::class.simpleName}), restarting in ${backoff}ms" }
            } catch (e: CommonBotException) {
                logger.warn { "Telegram long polling failed (${e::class.simpleName}), restarting in ${backoff}ms" }
            }
            val elapsedMs = (System.nanoTime() - startNanos) / NANOS_PER_MS
            if (elapsedMs > BACKOFF_RESET_THRESHOLD_MS) {
                backoff = INITIAL_POLL_BACKOFF_MS
            }
            delay(backoff)
            backoff = minOf(backoff * 2, MAX_POLL_BACKOFF_MS)
        }
    }

    private suspend fun runLongPolling(
        b: TelegramBot,
        onMessage: suspend (IncomingMessage) -> Unit,
    ) {
        pollingJob =
            b.buildBehaviourWithLongPolling(scope = scope) {
                onText { message ->
                    val chatId = message.chat.id.chatId.long
                    val text = message.content.text
                    val fromUser = (message as? dev.inmo.tgbotapi.abstracts.FromUser)?.from
                    val fromUserId = fromUser?.id?.chatId?.long
                    val senderName =
                        fromUser?.let { user ->
                            buildString {
                                append(user.firstName)
                                if (user.lastName.isNotEmpty()) append(" ${user.lastName}")
                            }
                        }
                    val chat = message.chat
                    val chatTypeStr =
                        when (chat) {
                            is dev.inmo.tgbotapi.types.chat.PrivateChat -> "private"
                            is dev.inmo.tgbotapi.types.chat.SupergroupChat -> "supergroup"
                            is dev.inmo.tgbotapi.types.chat.GroupChat -> "group"
                            is dev.inmo.tgbotapi.types.chat.ChannelChat -> "channel"
                            else -> "unknown"
                        }
                    val chatTitleStr =
                        (chat as? dev.inmo.tgbotapi.types.chat.PublicChat)?.title
                    val platformMsgId = message.messageId.long.toString()
                    val incoming =
                        TelegramNormalizer.normalize(
                            chatId = chatId,
                            text = text,
                            userId = fromUserId,
                            senderName = senderName,
                            chatType = chatTypeStr,
                            chatTitle = chatTitleStr,
                            platformMessageId = platformMsgId,
                        )
                    chatTypes[incoming.chatId] = chatTypeStr
                    startTyping(incoming.chatId, chatId)
                    logger.trace { "Telegram update received: chatId=$chatId isCommand=${incoming.isCommand}" }
                    runCatching {
                        jsonlWriter.writeInbound(incoming)
                        onMessage(incoming)
                    }.onFailure { e ->
                        logger.error(e) { "Error processing Telegram message" }
                    }
                }
                onPhoto { message ->
                    handlePhotoMessage(message, onMessage)
                }
                onDataCallbackQuery { query ->
                    val data = query.data
                    if (data.startsWith("approval:")) {
                        handleApprovalCallback(data, query.id.string)
                    }
                }
            }
        pollingJob?.join()
    }

    override suspend fun sendStreamDelta(
        chatId: String,
        delta: String,
        streamId: String,
    ) {
        val chatType = chatTypes[chatId]
        if (chatType != "private") return

        val platformId = chatId.removePrefix("telegram_").toLongOrNull() ?: return
        val state =
            streamStates.getOrPut(chatId) {
                TelegramStreamState(
                    draftId = streamId.hashCode().toLong().and(MAX_DRAFT_ID) or 1L,
                    accumulatedText = StringBuffer(),
                )
            }
        state.accumulatedText.append(delta)

        val now = System.currentTimeMillis()
        if (now - state.lastSendTimeMs < STREAM_THROTTLE_MS) return
        state.lastSendTimeMs = now

        try {
            draftAction?.invoke(platformId, state.draftId, state.accumulatedText.toString())
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            logger.trace { "sendMessageDraft failed for chatId=$chatId" }
        }
    }

    override suspend fun sendStreamEnd(
        chatId: String,
        fullContent: String,
        streamId: String,
    ) {
        streamStates.remove(chatId)
        stopTyping(chatId)
        send(chatId, OutgoingMessage(fullContent))
    }

    override suspend fun send(
        chatId: String,
        response: OutgoingMessage,
    ) {
        stopTyping(chatId)
        if (bot == null && sendAction == null) {
            logger.warn { "TelegramChannel.send called but bot not started" }
            return
        }
        val platformId =
            chatId.removePrefix("telegram_").toLongOrNull() ?: run {
                logger.warn { "Invalid chatId format: $chatId" }
                return
            }
        val chunks = splitMessage(response.content)
        logger.trace { "Sending message to Telegram chatId=$chatId chunks=${chunks.size}" }
        for (chunk in chunks) {
            runCatching {
                withSendRetry {
                    val action = sendAction
                    if (action != null) {
                        action(platformId, chunk)
                    } else {
                        bot!!.sendTextMessage(ChatId(RawChatId(platformId)), chunk)
                    }
                }
            }.onSuccess {
                setAlive(true)
            }.onFailure { e ->
                if (isConnectivityError(e)) {
                    alive = false
                }
                logger.error(e) { "Failed to send Telegram message to chatId=$chatId" }
                return
            }
        }
    }

    override suspend fun sendApproval(
        chatId: String,
        request: ApprovalRequestMessage,
        onResult: suspend (Boolean) -> Unit,
    ) {
        stopTyping(chatId)
        if (bot == null && sendApprovalAction == null) {
            logger.warn { "TelegramChannel.sendApproval called but bot not started" }
            return
        }
        val platformId =
            chatId.removePrefix("telegram_").toLongOrNull() ?: run {
                logger.warn { "Invalid chatId format for approval: $chatId" }
                return
            }
        pendingApprovals[request.id] = onResult
        logger.trace { "Sending approval request to Telegram chatId=$chatId" }
        runCatching {
            val keyboard =
                InlineKeyboardMarkup(
                    keyboard =
                        listOf(
                            listOf(
                                CallbackDataInlineKeyboardButton("Approve", "approval:${request.id}:yes"),
                                CallbackDataInlineKeyboardButton("Reject", "approval:${request.id}:no"),
                            ),
                        ),
                )
            val messageId =
                withSendRetry {
                    val action = sendApprovalAction
                    if (action != null) {
                        action(platformId, request.command, keyboard)
                    } else {
                        val sent =
                            bot!!.sendTextMessage(
                                chatId = ChatId(RawChatId(platformId)),
                                text =
                                    "Command approval requested:\n\n${request.command}" +
                                        "\n\nRisk score: ${request.riskScore}/10",
                                replyMarkup = keyboard,
                            )
                        sent.messageId.long
                    }
                }
            approvalMessageIds[request.id] = Pair(platformId, messageId)
        }.onFailure { e ->
            pendingApprovals.remove(request.id)
            logger.error(e) { "Failed to send approval request to chatId=$chatId" }
        }
    }

    private suspend fun handlePhotoMessage(
        message: dev.inmo.tgbotapi.types.message.abstracts.CommonMessage<
            dev.inmo.tgbotapi.types.message.content.PhotoContent,
        >,
        onMessage: suspend (IncomingMessage) -> Unit,
    ) {
        val chatId = message.chat.id.chatId.long
        val caption = message.content.text.orEmpty()
        val fromUser = (message as? dev.inmo.tgbotapi.abstracts.FromUser)?.from
        val fromUserId = fromUser?.id?.chatId?.long
        val senderName =
            fromUser?.let { user ->
                buildString {
                    append(user.firstName)
                    if (user.lastName.isNotEmpty()) append(" ${user.lastName}")
                }
            }
        val chat = message.chat
        val chatTypeStr =
            when (chat) {
                is dev.inmo.tgbotapi.types.chat.PrivateChat -> "private"
                is dev.inmo.tgbotapi.types.chat.SupergroupChat -> "supergroup"
                is dev.inmo.tgbotapi.types.chat.GroupChat -> "group"
                is dev.inmo.tgbotapi.types.chat.ChannelChat -> "channel"
                else -> "unknown"
            }
        val chatTitleStr = (chat as? dev.inmo.tgbotapi.types.chat.PublicChat)?.title
        val platformMsgId = message.messageId.long.toString()

        val attachments = downloadAndSavePhoto(message, chatId)

        val incoming =
            TelegramNormalizer.normalize(
                chatId = chatId,
                text = caption,
                userId = fromUserId,
                senderName = senderName,
                chatType = chatTypeStr,
                chatTitle = chatTitleStr,
                platformMessageId = platformMsgId,
                attachments = attachments,
            )
        chatTypes[incoming.chatId] = chatTypeStr
        startTyping(incoming.chatId, chatId)
        logger.trace { "Telegram photo received: chatId=$chatId attachments=${attachments.size}" }
        runCatching {
            jsonlWriter.writeInbound(incoming)
            onMessage(incoming)
        }.onFailure { e ->
            logger.error(e) { "Error processing Telegram photo message" }
        }
    }

    private suspend fun downloadAndSavePhoto(
        message: dev.inmo.tgbotapi.types.message.abstracts.CommonMessage<
            dev.inmo.tgbotapi.types.message.content.PhotoContent,
        >,
        chatId: Long,
    ): List<AttachmentInfo> {
        val attachmentsDir = config.attachments.directory
        if (attachmentsDir.isBlank()) {
            logger.debug { "Attachments directory not configured, skipping photo download" }
            return emptyList()
        }
        val b = bot ?: return emptyList()
        return try {
            val photoSizes = message.content.mediaCollection
            val largest = photoSizes.maxByOrNull { it.width.toLong() * it.height } ?: return emptyList()
            val fileInfo = b.getFileAdditionalInfo(largest)
            val fileBytes = b.downloadFile(fileInfo)
            val chatDir = File(attachmentsDir, "telegram_$chatId")
            chatDir.mkdirs()
            val filePath = fileInfo.filePath
            val ext = filePath.substringAfterLast('.', "jpg")
            val fileName = "${UUID.randomUUID()}.$ext"
            val savedFile = File(chatDir, fileName)
            savedFile.writeBytes(fileBytes)
            logger.debug { "Telegram photo saved: ${savedFile.absolutePath.length} path chars" }
            listOf(
                AttachmentInfo(
                    path = savedFile.absolutePath,
                    mimeType = detectMimeType(savedFile.name),
                    originalName = filePath.substringAfterLast('/'),
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.warn(e) { "Failed to download Telegram photo for chatId=$chatId" }
            emptyList()
        }
    }

    internal suspend fun handleApprovalCallback(
        data: String,
        callbackQueryId: String,
    ) {
        // Format: approval:{id}:{yes|no}
        val parts = data.split(":")
        if (parts.size != APPROVAL_CALLBACK_PARTS) return
        val approvalId = parts[1]
        val approved = parts[2] == "yes"
        val callback = pendingApprovals.remove(approvalId)
        if (callback == null) {
            logger.debug { "No pending approval for id=$approvalId" }
            return
        }
        val statusText = if (approved) "Approved" else "Rejected"
        answerApprovalCallbackQuery(callbackQueryId, statusText)
        editApprovalMessage(approvalId)
        callback(approved)
        logger.debug { "Approval callback processed: id=$approvalId approved=$approved" }
    }

    private suspend fun answerApprovalCallbackQuery(
        callbackQueryId: String,
        text: String,
    ) {
        try {
            val action = answerCallbackAction
            if (action != null) {
                action(callbackQueryId, text)
            } else {
                bot?.answerCallbackQuery(
                    callbackQueryId = CallbackQueryId(callbackQueryId),
                    text = text,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CommonBotException) {
            logger.warn(e) { "Failed to answer callback query" }
        } catch (e: IOException) {
            logger.warn(e) { "Failed to answer callback query" }
        }
    }

    private suspend fun editApprovalMessage(approvalId: String) {
        val messageInfo = approvalMessageIds.remove(approvalId) ?: return
        val (platformChatId, messageId) = messageInfo
        try {
            val action = editMessageAction
            if (action != null) {
                action(platformChatId, messageId)
            } else {
                bot?.editMessageReplyMarkup(
                    chatId = ChatId(RawChatId(platformChatId)),
                    messageId = MessageId(messageId),
                    replyMarkup = null,
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CommonBotException) {
            logger.warn(e) { "Failed to edit approval message" }
        } catch (e: IOException) {
            logger.warn(e) { "Failed to edit approval message" }
        }
    }

    internal fun startTyping(
        chatId: String,
        platformId: Long,
    ) {
        typingJobs.compute(chatId) { _, existingJob ->
            existingJob?.cancel()
            typingScope.launch {
                while (isActive) {
                    try {
                        typingAction(platformId)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: CommonBotException) {
                        logger.warn(e) { "Typing action failed for chatId=$chatId: ${e::class.simpleName}" }
                    } catch (e: IOException) {
                        logger.warn(e) { "Typing action failed for chatId=$chatId: ${e::class.simpleName}" }
                    }
                    delay(TYPING_REFRESH_INTERVAL_MS)
                }
            }
        }
        logger.trace { "Typing started for chatId=$chatId" }
    }

    internal fun stopTyping(chatId: String) {
        val cancelled = typingJobs.remove(chatId)?.cancel()
        if (cancelled != null) {
            logger.trace { "Typing stopped for chatId=$chatId" }
        }
    }

    override suspend fun stop() {
        alive = false
        pollingJob?.cancel()
        typingJobs.values.forEach { it.cancel() }
        typingJobs.clear()
        pendingApprovals.clear()
        approvalMessageIds.clear()
        streamStates.clear()
        chatTypes.clear()
        logger.info { "TelegramChannel stopped" }
    }

    private fun isConnectivityError(e: Throwable): Boolean =
        e is IOException || e is CommonBotException || e is HttpRequestTimeoutException

    companion object {
        private const val APPROVAL_CALLBACK_PARTS = 3
        private const val TYPING_REFRESH_INTERVAL_MS = 4_000L
        private const val INITIAL_POLL_BACKOFF_MS = 2_000L
        private const val MAX_POLL_BACKOFF_MS = 60_000L
        private const val BACKOFF_RESET_THRESHOLD_MS = 30_000L
        private const val NANOS_PER_MS = 1_000_000L
        private const val STREAM_THROTTLE_MS = 300L
        private const val MAX_DRAFT_ID = 0x7FFFFFFFL
    }
}

internal data class TelegramStreamState(
    val draftId: Long,
    val accumulatedText: StringBuffer,
    var lastSendTimeMs: Long = 0,
)
