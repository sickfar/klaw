package io.github.klaw.gateway.channel

import java.util.UUID
import kotlin.time.Clock
import kotlin.time.Instant

object DiscordNormalizer {
    const val DISCORD_MAX_MESSAGE_LENGTH = 2000

    @Suppress("LongParameterList")
    fun normalize(
        channelId: ULong,
        text: String,
        userId: ULong? = null,
        ts: Instant = Clock.System.now(),
        messageId: String = UUID.randomUUID().toString(),
        senderName: String? = null,
        chatType: String? = null,
        chatTitle: String? = null,
        platformMessageId: String? = null,
        guildId: String? = null,
        attachments: List<AttachmentInfo> = emptyList(),
        agentId: String = "default",
    ): IncomingMessage {
        val parsed = CommandParser.parse(text)
        return IncomingMessage(
            id = messageId,
            channel = "discord",
            chatId = "discord_$channelId",
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
            guildId = guildId,
            attachments = attachments,
        )
    }
}
