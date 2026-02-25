package io.github.klaw.engine.socket

import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.protocol.InboundSocketMessage
import jakarta.inject.Singleton

@Singleton
@Suppress("EmptyFunctionBlock")
class NoOpSocketMessageHandler : SocketMessageHandler {
    override suspend fun handleInbound(message: InboundSocketMessage) {}

    override suspend fun handleCommand(message: CommandSocketMessage) {}

    override suspend fun handleCliRequest(request: CliRequestMessage): String = """{"status":"ok"}"""
}
