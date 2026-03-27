package io.github.klaw.engine.workspace

import io.github.klaw.common.protocol.OutboundSocketMessage

data class HeartbeatPersistence(
    val jsonlWriter: HeartbeatJsonlWriter,
    val persistDelivered: suspend (channel: String, chatId: String, content: String) -> Unit,
    val pushToGateway: suspend (OutboundSocketMessage) -> Unit,
)
