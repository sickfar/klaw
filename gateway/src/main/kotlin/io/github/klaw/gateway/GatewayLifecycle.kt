package io.github.klaw.gateway

import io.github.klaw.common.protocol.Attachment
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.channel.CommandParser
import io.github.klaw.gateway.channel.IncomingMessage
import io.github.klaw.gateway.pairing.ConfigFileWatcher
import io.github.klaw.gateway.pairing.InboundAllowlistService
import io.github.klaw.gateway.pairing.PairingCodeResult
import io.github.klaw.gateway.pairing.PairingService
import io.github.klaw.gateway.pairing.PairingStatus
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
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@Singleton
class GatewayLifecycle(
    private val channels: List<Channel>,
    private val engineClient: EngineSocketClient,
    private val outboundHandler: GatewayOutboundHandler,
    private val allowlistService: InboundAllowlistService,
    private val pairingService: PairingService,
    private val configFileWatcher: ConfigFileWatcher,
) : ApplicationEventListener<StartupEvent> {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onApplicationEvent(event: StartupEvent) {
        configFileWatcher.startWatching { newConfig ->
            allowlistService.reload(newConfig)
            logger.debug { "Config reloaded after file change" }
        }
        outboundHandler.approvalCallback = { msg -> engineClient.send(msg) }
        engineClient.start()
        if (channels.isEmpty()) {
            logger.warn { "GatewayLifecycle starting with no channels — gateway will not process any messages" }
        }
        logger.info { "GatewayLifecycle starting ${channels.size} channel(s)" }
        channels.forEach { channel ->
            scope.launch {
                channel.start()
                channel.listen(
                    buildInboundCallback(
                        allowlistService = allowlistService,
                        pairingService = pairingService,
                        configFileWatcher = configFileWatcher,
                        engineClient = engineClient,
                        replyFn = { chatId, msg ->
                            channel.send(
                                chatId,
                                io.github.klaw.gateway.channel
                                    .OutgoingMessage(msg),
                            )
                        },
                    ),
                )
            }
        }
    }

    @PreDestroy
    fun stop() {
        configFileWatcher.stopWatching()
        scope.cancel()
        runBlocking { channels.forEach { it.stop() } }
        logger.info { "GatewayLifecycle stopped" }
    }

    companion object {
        @Suppress("LongParameterList")
        fun buildInboundCallback(
            allowlistService: InboundAllowlistService,
            pairingService: PairingService,
            configFileWatcher: ConfigFileWatcher,
            engineClient: EngineSocketClient,
            replyFn: suspend (chatId: String, message: String) -> Unit,
            confirmationExpiryMs: Long = CONFIRMATION_EXPIRY_MS,
        ): suspend (IncomingMessage) -> Unit =
            callback@{ incoming ->
                val isCmd: Boolean
                val cmdName: String?
                val cmdArgs: String?
                if (incoming.isCommand) {
                    isCmd = true
                    cmdName = incoming.commandName
                    cmdArgs = incoming.commandArgs
                } else {
                    val parsed = CommandParser.parse(incoming.content)
                    isCmd = parsed.isCommand
                    cmdName = parsed.commandName
                    cmdArgs = parsed.commandArgs
                }

                // Handle /start command — never forwarded to engine
                if (isCmd && cmdName == "start") {
                    handleStartCommand(
                        incoming = incoming,
                        allowlistService = allowlistService,
                        pairingService = pairingService,
                        configFileWatcher = configFileWatcher,
                        replyFn = replyFn,
                        confirmationExpiryMs = confirmationExpiryMs,
                    )
                    return@callback
                }

                // Inbound allowlist check
                if (!allowlistService.isAllowed(incoming.channel, incoming.chatId, incoming.userId)) {
                    logger.debug { "Inbound blocked: chatId=${incoming.chatId} not paired" }
                    replyFn(incoming.chatId, "Not paired. Send /start to get a pairing code.")
                    return@callback
                }

                // Forward to engine
                forwardToEngine(engineClient, incoming, isCmd, cmdName, cmdArgs)
            }

        @Suppress("LongParameterList")
        private fun forwardToEngine(
            engineClient: EngineSocketClient,
            incoming: IncomingMessage,
            isCmd: Boolean,
            cmdName: String?,
            cmdArgs: String?,
        ) {
            if (isCmd) {
                engineClient.send(
                    CommandSocketMessage(
                        channel = incoming.channel,
                        chatId = incoming.chatId,
                        command = cmdName ?: "",
                        args = cmdArgs,
                        senderId = incoming.userId,
                        senderName = incoming.senderName,
                        chatType = incoming.chatType,
                        chatTitle = incoming.chatTitle,
                        messageId = incoming.messageId,
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
                        senderId = incoming.userId,
                        senderName = incoming.senderName,
                        chatType = incoming.chatType,
                        chatTitle = incoming.chatTitle,
                        messageId = incoming.messageId,
                        attachments =
                            incoming.attachments.map { att ->
                                Attachment(
                                    path = att.path,
                                    mimeType = att.mimeType,
                                    originalName = att.originalName,
                                )
                            },
                    ),
                )
                logger.debug {
                    "Inbound forwarded to engine: channel=${incoming.channel} attachments=${incoming.attachments.size}"
                }
            }
        }

        private fun registerConfirmationListener(
            incoming: IncomingMessage,
            allowlistService: InboundAllowlistService,
            configFileWatcher: ConfigFileWatcher,
            replyFn: suspend (chatId: String, message: String) -> Unit,
            expiryMs: Long = CONFIRMATION_EXPIRY_MS,
        ) {
            val confirmationSent = AtomicBoolean(false)
            lateinit var listener: (io.github.klaw.common.config.GatewayConfig) -> Unit
            listener = { _ ->
                if (allowlistService.isChatAllowed(incoming.channel, incoming.chatId) &&
                    confirmationSent.compareAndSet(false, true)
                ) {
                    configFileWatcher.removeListener(listener)
                    runBlocking {
                        replyFn(incoming.chatId, "Pairing successful! You can now send me messages.")
                    }
                } else {
                    logger.trace { "No confirmation sent for chatId=${incoming.chatId}" }
                }
            }
            configFileWatcher.addListener(listener)

            // Auto-cleanup after code expiry to prevent memory leak from unpaired /start commands
            CLEANUP_EXECUTOR.schedule(
                {
                    if (confirmationSent.compareAndSet(false, true)) {
                        configFileWatcher.removeListener(listener)
                        logger.debug { "Confirmation listener expired for chatId=${incoming.chatId}" }
                    }
                },
                expiryMs,
                java.util.concurrent.TimeUnit.MILLISECONDS,
            )
        }

        private const val CONFIRMATION_EXPIRY_MS = 5L * 60 * 1000
        private val CLEANUP_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "pairing-confirmation-cleanup").apply { isDaemon = true }
            }

        @Suppress("LongParameterList")
        private suspend fun handleStartCommand(
            incoming: IncomingMessage,
            allowlistService: InboundAllowlistService,
            pairingService: PairingService,
            configFileWatcher: ConfigFileWatcher,
            replyFn: suspend (chatId: String, message: String) -> Unit,
            confirmationExpiryMs: Long = CONFIRMATION_EXPIRY_MS,
        ) {
            val status = allowlistService.isStartAllowed(incoming.channel, incoming.chatId, incoming.userId)
            when (status) {
                PairingStatus.AlreadyPaired -> {
                    replyFn(incoming.chatId, "Already paired.")
                    logger.debug { "Start command from already paired chatId=${incoming.chatId}" }
                }

                PairingStatus.NewChat, PairingStatus.NewUserInExistingChat -> {
                    val result =
                        pairingService.generateCode(
                            incoming.channel,
                            incoming.chatId,
                            incoming.userId,
                            incoming.guildId,
                        )
                    when (result) {
                        is PairingCodeResult.Success -> {
                            replyFn(
                                incoming.chatId,
                                "Pairing code: ${result.code}\n\n" +
                                    "Run on the server: klaw pair ${incoming.channel} ${result.code}\n\n" +
                                    "Code expires in 5 minutes.",
                            )
                            if (pairingService.hasPendingRequests()) {
                                registerConfirmationListener(
                                    incoming,
                                    allowlistService,
                                    configFileWatcher,
                                    replyFn,
                                    confirmationExpiryMs,
                                )
                            }
                            logger.debug { "Pairing code generated for chatId=${incoming.chatId}" }
                        }

                        is PairingCodeResult.RateLimited -> {
                            replyFn(incoming.chatId, "Please wait before requesting a new pairing code.")
                            logger.trace { "Rate limited /start from chatId=${incoming.chatId}" }
                        }

                        is PairingCodeResult.AlreadyPaired -> {
                            replyFn(incoming.chatId, "Already paired.")
                        }
                    }
                }
            }
        }
    }
}
