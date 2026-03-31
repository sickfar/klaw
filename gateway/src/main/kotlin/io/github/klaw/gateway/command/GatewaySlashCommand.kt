package io.github.klaw.gateway.command

import io.github.klaw.common.command.SlashCommand
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.channel.IncomingMessage

interface GatewaySlashCommand : SlashCommand {
    suspend fun handle(
        msg: IncomingMessage,
        channel: Channel,
    )
}
