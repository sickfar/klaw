package io.github.klaw.engine.workspace

import io.github.klaw.common.protocol.ApprovalDismissMessage
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.tools.ApprovalService
import jakarta.inject.Singleton

@Singleton
class HeartbeatApprovalBridge(
    private val approvalService: ApprovalService,
    private val socketServer: EngineSocketServer,
) {
    fun denyPendingForChatId(chatId: String): List<String> = approvalService.denyPendingForChatId(chatId)

    suspend fun sendDismiss(id: String) {
        socketServer.pushMessage(ApprovalDismissMessage(id))
    }
}
