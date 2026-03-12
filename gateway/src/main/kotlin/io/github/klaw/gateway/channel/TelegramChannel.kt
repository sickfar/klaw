package io.github.klaw.gateway.channel

import dev.inmo.kslog.common.filter.filtered
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.exceptions.CommonBotException
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.extensions.api.send.sendActionTyping
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onDataCallbackQuery
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.ChatId
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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private val logger = KotlinLogging.logger {}

@Singleton
class TelegramChannel(
    private val config: GatewayConfig,
    private val jsonlWriter: ConversationJsonlWriter,
) : Channel {
    override val name = "telegram"
    private var bot: TelegramBot? = null
    private var pollingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val pendingApprovals = ConcurrentHashMap<String, suspend (Boolean) -> Unit>()
    internal val typingJobs = ConcurrentHashMap<String, Job>()
    internal var typingAction: suspend (Long) -> Unit = { platformId ->
        bot?.sendActionTyping(ChatId(RawChatId(platformId)))
    }
    internal var typingScope: CoroutineScope = scope
    internal var listenScope: CoroutineScope = scope
    internal var pollOnce: (suspend (suspend (IncomingMessage) -> Unit) -> Unit)? = null
    internal var sendAction: (suspend (Long, String) -> Unit)? = null
    internal var sendApprovalAction: (suspend (Long, String, InlineKeyboardMarkup) -> Unit)? = null
    internal val pendingApprovalsForTest: Map<String, suspend (Boolean) -> Unit> get() = pendingApprovals

    override suspend fun start() {
        val telegramConfig =
            config.channels.telegram ?: run {
                logger.info { "Telegram config not found, TelegramChannel not started" }
                return
            }
        logger.info { "Starting TelegramChannel" }
        val b =
            telegramBot(telegramConfig.token) {
                logger =
                    logger.filtered { _, _, throwable ->
                        throwable !is HttpRequestTimeoutException &&
                            throwable?.cause !is HttpRequestTimeoutException
                    }
            }
        bot = b
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
                    val fromUserId =
                        (message as? dev.inmo.tgbotapi.abstracts.FromUser)
                            ?.from
                            ?.id
                            ?.chatId
                            ?.long
                    val incoming =
                        TelegramNormalizer.normalize(
                            chatId = chatId,
                            text = text,
                            userId = fromUserId,
                        )
                    startTyping(incoming.chatId, chatId)
                    logger.trace { "Telegram update received: chatId=$chatId isCommand=${incoming.isCommand}" }
                    runCatching {
                        jsonlWriter.writeInbound(incoming)
                        onMessage(incoming)
                    }.onFailure { e ->
                        logger.error(e) { "Error processing Telegram message" }
                    }
                }
                onDataCallbackQuery { query ->
                    val data = query.data
                    if (data.startsWith("approval:")) {
                        handleApprovalCallback(data, query)
                    }
                }
            }
        pollingJob?.join()
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
            }.onFailure { e ->
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
            withSendRetry {
                val action = sendApprovalAction
                if (action != null) {
                    action(platformId, request.command, keyboard)
                } else {
                    bot!!.sendTextMessage(
                        chatId = ChatId(RawChatId(platformId)),
                        text =
                            "Command approval requested:\n\n${request.command}" +
                                "\n\nRisk score: ${request.riskScore}/10",
                        replyMarkup = keyboard,
                    )
                }
            }
        }.onFailure { e ->
            pendingApprovals.remove(request.id)
            logger.error(e) { "Failed to send approval request to chatId=$chatId" }
        }
    }

    private suspend fun handleApprovalCallback(
        data: String,
        @Suppress("UNUSED_PARAMETER") query: dev.inmo.tgbotapi.types.queries.callback.DataCallbackQuery,
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
        callback(approved)
        logger.debug { "Approval callback processed: id=$approvalId approved=$approved" }
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
        pollingJob?.cancel()
        typingJobs.values.forEach { it.cancel() }
        typingJobs.clear()
        logger.info { "TelegramChannel stopped" }
    }

    companion object {
        private const val APPROVAL_CALLBACK_PARTS = 3
        private const val TYPING_REFRESH_INTERVAL_MS = 4_000L
        private const val INITIAL_POLL_BACKOFF_MS = 2_000L
        private const val MAX_POLL_BACKOFF_MS = 60_000L
        private const val BACKOFF_RESET_THRESHOLD_MS = 30_000L
        private const val NANOS_PER_MS = 1_000_000L
    }
}
