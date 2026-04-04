package io.github.klaw.gateway.channel

import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

object TelegramNormalizer {
    @Suppress("LongParameterList")
    fun normalize(
        chatId: Long,
        text: String,
        userId: Long? = null,
        ts: Instant = Clock.System.now(),
        messageId: String = UUID.randomUUID().toString(),
        senderName: String? = null,
        chatType: String? = null,
        chatTitle: String? = null,
        platformMessageId: String? = null,
        attachments: List<AttachmentInfo> = emptyList(),
        agentId: String = "default",
        channelName: String = "telegram",
    ): IncomingMessage {
        val parsed = CommandParser.parse(text)
        return IncomingMessage(
            id = messageId,
            channel = channelName,
            chatId = "telegram_$chatId",
            content = text,
            ts = ts,
            agentId = agentId,
            userId = userId?.toString(),
            isCommand = parsed.isCommand,
            commandName = parsed.commandName,
            commandArgs = parsed.commandArgs,
            senderName = senderName,
            chatType = chatType,
            chatTitle = chatTitle,
            messageId = platformMessageId,
            attachments = attachments,
        )
    }
}
