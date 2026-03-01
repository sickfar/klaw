package io.github.klaw.engine.tools

import io.github.klaw.engine.socket.EngineSocketServer
import io.micronaut.context.annotation.Factory
import jakarta.inject.Provider
import jakarta.inject.Singleton

@Factory
class ApprovalServiceFactory {
    @Singleton
    fun approvalService(socketServerProvider: Provider<EngineSocketServer>): ApprovalService =
        ApprovalService { message ->
            socketServerProvider.get().pushMessage(message)
        }
}
