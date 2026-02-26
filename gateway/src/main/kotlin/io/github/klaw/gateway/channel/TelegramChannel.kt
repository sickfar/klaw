package io.github.klaw.gateway.channel

import dev.inmo.tgbotapi.bot.TelegramBot
import dev.inmo.tgbotapi.bot.ktor.telegramBot
import dev.inmo.tgbotapi.extensions.api.bot.setMyCommands
import dev.inmo.tgbotapi.extensions.api.send.sendTextMessage
import dev.inmo.tgbotapi.extensions.behaviour_builder.buildBehaviourWithLongPolling
import dev.inmo.tgbotapi.extensions.behaviour_builder.triggers_handling.onText
import dev.inmo.tgbotapi.types.BotCommand
import dev.inmo.tgbotapi.types.ChatId
import dev.inmo.tgbotapi.types.RawChatId
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

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

    override suspend fun start() {
        val telegramConfig =
            config.channels.telegram ?: run {
                logger.info { "Telegram config not found, TelegramChannel not started" }
                return
            }
        logger.info { "Starting TelegramChannel" }
        val b = telegramBot(telegramConfig.token)
        bot = b
        if (config.commands.isNotEmpty()) {
            runCatching {
                b.setMyCommands(config.commands.map { BotCommand(it.name, it.description) })
                logger.debug { "Registered ${config.commands.size} bot commands with Telegram" }
            }.onFailure { e ->
                logger.warn { "Failed to register bot commands: ${e::class.simpleName}" }
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
                    val incoming =
                        TelegramNormalizer.normalize(
                            chatId = chatId,
                            text = text,
                        )
                    logger.trace { "Telegram update received: chatId=$chatId isCommand=${incoming.isCommand}" }
                    runCatching {
                        jsonlWriter.writeInbound(incoming)
                        onMessage(incoming)
                    }.onFailure { e ->
                        logger.error(e) { "Error processing Telegram message" }
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

    override suspend fun stop() {
        pollingJob?.cancel()
        logger.info { "TelegramChannel stopped" }
    }
}
