package io.github.klaw.gateway.channel

import dev.inmo.kslog.common.filter.filtered
import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import io.ktor.client.plugins.HttpRequestTimeoutException
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
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
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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
        val b = bot ?: return
        logger.debug { "TelegramChannel starting long polling" }
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
        val b =
            bot ?: run {
                logger.warn { "TelegramChannel.send called but bot not started" }
                return
            }
        val platformId =
            chatId.removePrefix("telegram_").toLongOrNull() ?: run {
                logger.warn { "Invalid chatId format: $chatId" }
                return
            }
        logger.trace { "Sending message to Telegram chatId=$chatId" }
        runCatching {
            b.sendTextMessage(ChatId(RawChatId(platformId)), response.content)
        }.onFailure { e ->
            logger.error(e) { "Failed to send Telegram message to chatId=$chatId" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun sendApproval(
        chatId: String,
        request: ApprovalRequestMessage,
        onResult: suspend (Boolean) -> Unit,
    ) {
        val b =
            bot ?: run {
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
        try {
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
            b.sendTextMessage(
                chatId = ChatId(RawChatId(platformId)),
                text = "Command approval requested:\n\n${request.command}\n\nRisk score: ${request.riskScore}/10",
                replyMarkup = keyboard,
            )
        } catch (e: Exception) {
            pendingApprovals.remove(request.id)
            logger.error(e) { "Failed to send approval request to chatId=$chatId" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
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

    override suspend fun stop() {
        pollingJob?.cancel()
        logger.info { "TelegramChannel stopped" }
    }

    companion object {
        private const val APPROVAL_CALLBACK_PARTS = 3
    }
}
