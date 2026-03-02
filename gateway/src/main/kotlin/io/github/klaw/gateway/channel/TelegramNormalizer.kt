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
        val parsed = CommandParser.parse(text)
        return IncomingMessage(
            id = messageId,
            channel = "telegram",
            chatId = "telegram_$chatId",
            content = text,
            ts = ts,
            isCommand = parsed.isCommand,
            commandName = parsed.commandName,
            commandArgs = parsed.commandArgs,
        )
    }
}
