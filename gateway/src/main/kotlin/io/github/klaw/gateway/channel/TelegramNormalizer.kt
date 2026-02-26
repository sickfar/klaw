package io.github.klaw.gateway.channel

import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

object TelegramNormalizer {
    fun normalize(
        chatId: Long,
        text: String,
        ts: Instant = Clock.System.now(),
        messageId: String = UUID.randomUUID().toString(),
    ): IncomingMessage {
        val isCommand = text.startsWith("/")
        val parts = if (isCommand) text.drop(1).split(" ", limit = 2) else emptyList()
        val commandName = parts.firstOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }
        val commandArgs = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
        return IncomingMessage(
            id = messageId,
            channel = "telegram",
            chatId = "telegram_$chatId",
            content = text,
            ts = ts,
            isCommand = isCommand,
            commandName = commandName,
            commandArgs = commandArgs,
        )
    }
}
