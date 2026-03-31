package io.github.klaw.gateway.command.commands

import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.channel.IncomingMessage
import io.github.klaw.gateway.channel.OutgoingMessage
import io.github.klaw.gateway.command.GatewaySlashCommand
import io.github.klaw.gateway.pairing.ConfigFileWatcher
import io.github.klaw.gateway.pairing.InboundAllowlistService
import io.github.klaw.gateway.pairing.PairingCodeResult
import io.github.klaw.gateway.pairing.PairingService
import io.github.klaw.gateway.pairing.PairingStatus
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@Singleton
class StartGatewayCommand(
    private val pairingService: PairingService,
    private val inboundAllowlistService: InboundAllowlistService,
    private val configFileWatcher: ConfigFileWatcher,
) : GatewaySlashCommand {
    override val name = "start"
    override val description = "Pair this chat with Klaw"

    override suspend fun handle(
        msg: IncomingMessage,
        channel: Channel,
    ) {
        val status = inboundAllowlistService.isStartAllowed(msg.channel, msg.chatId, msg.userId)
        when (status) {
            PairingStatus.AlreadyPaired -> {
                channel.send(msg.chatId, OutgoingMessage("Already paired."))
                logger.debug { "Start command from already paired chatId=${msg.chatId}" }
            }

            PairingStatus.NewChat, PairingStatus.NewUserInExistingChat -> {
                val result =
                    pairingService.generateCode(
                        msg.channel,
                        msg.chatId,
                        msg.userId,
                        msg.guildId,
                    )
                when (result) {
                    is PairingCodeResult.Success -> {
                        channel.send(
                            msg.chatId,
                            OutgoingMessage(
                                "Pairing code: ${result.code}\n\n" +
                                    "Run on the server: klaw channels pair ${msg.channel} ${result.code}\n\n" +
                                    "Code expires in 5 minutes.",
                            ),
                        )
                        if (pairingService.hasPendingRequests()) {
                            registerConfirmationListener(msg, channel)
                        }
                        logger.debug { "Pairing code generated for chatId=${msg.chatId}" }
                    }

                    is PairingCodeResult.RateLimited -> {
                        channel.send(msg.chatId, OutgoingMessage("Please wait before requesting a new pairing code."))
                        logger.trace { "Rate limited /start from chatId=${msg.chatId}" }
                    }

                    is PairingCodeResult.AlreadyPaired -> {
                        channel.send(msg.chatId, OutgoingMessage("Already paired."))
                    }
                }
            }
        }
    }

    private fun registerConfirmationListener(
        msg: IncomingMessage,
        channel: Channel,
    ) {
        val confirmationSent = AtomicBoolean(false)
        lateinit var listener: (io.github.klaw.common.config.GatewayConfig) -> Unit
        listener = { _ ->
            if (inboundAllowlistService.isChatAllowed(msg.channel, msg.chatId) &&
                confirmationSent.compareAndSet(false, true)
            ) {
                configFileWatcher.removeListener(listener)
                runBlocking {
                    channel.send(msg.chatId, OutgoingMessage("Pairing successful! You can now send me messages."))
                }
            } else {
                logger.trace { "No confirmation sent for chatId=${msg.chatId}" }
            }
        }
        configFileWatcher.addListener(listener)

        // Auto-cleanup after code expiry to prevent memory leak from unpaired /start commands
        CLEANUP_EXECUTOR.schedule(
            {
                if (confirmationSent.compareAndSet(false, true)) {
                    configFileWatcher.removeListener(listener)
                    logger.debug { "Confirmation listener expired for chatId=${msg.chatId}" }
                }
            },
            CONFIRMATION_EXPIRY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    companion object {
        private const val CONFIRMATION_EXPIRY_MS = 5L * 60 * 1000
        private val CLEANUP_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "pairing-confirmation-cleanup").apply { isDaemon = true }
            }
    }
}
