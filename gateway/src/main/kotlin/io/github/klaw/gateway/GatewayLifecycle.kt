package io.github.klaw.gateway

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.gateway.channel.TelegramChannel
import io.github.klaw.gateway.socket.EngineSocketClient
import io.github.klaw.gateway.socket.GatewayOutboundHandler
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.StartupEvent
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

@Singleton
class GatewayLifecycle(
    // TODO TASK-012: replace with List<Channel> when Discord is added
    private val telegramChannel: TelegramChannel,
    private val engineClient: EngineSocketClient,
    private val outboundHandler: GatewayOutboundHandler,
    private val config: GatewayConfig,
) : ApplicationEventListener<StartupEvent> {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onApplicationEvent(event: StartupEvent) {
        if (config.channels.telegram == null) {
            logger.warn { "No Telegram config — Gateway starting without channels" }
            return
        }
        logger.info { "GatewayLifecycle starting" }
        scope.launch {
            telegramChannel.start()
            telegramChannel.listen { incoming ->
                outboundHandler.addImplicitAllow(incoming.chatId)
                if (incoming.isCommand) {
                    engineClient.send(
                        CommandSocketMessage(
                            channel = incoming.channel,
                            chatId = incoming.chatId,
                            command = incoming.commandName ?: "",
                            args = incoming.commandArgs,
                        ),
                    )
                    logger.debug { "Command forwarded to engine: channel=${incoming.channel}" }
                } else {
                    engineClient.send(
                        InboundSocketMessage(
                            id = incoming.id,
                            channel = incoming.channel,
                            chatId = incoming.chatId,
                            content = incoming.content,
                            ts = incoming.ts.toString(),
                        ),
                    )
                    logger.debug { "Inbound forwarded to engine: channel=${incoming.channel}" }
                }
            }
        }
    }

    @PreDestroy
    fun stop() {
        scope.cancel()
        runBlocking { telegramChannel.stop() }
        logger.info { "GatewayLifecycle stopped" }
    }
}
