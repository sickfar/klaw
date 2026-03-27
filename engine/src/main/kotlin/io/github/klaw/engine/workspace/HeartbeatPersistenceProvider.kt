package io.github.klaw.engine.workspace

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.util.approximateTokenCount
import io.github.klaw.engine.message.MessageEmbeddingService
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.util.VT
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.nio.file.Path
import java.util.UUID

@Singleton
class HeartbeatPersistenceProvider(
    private val config: EngineConfig,
    private val socketServer: EngineSocketServer,
    private val messageRepository: MessageRepository,
    private val messageEmbeddingService: MessageEmbeddingService,
) {
    private val embeddingScope = CoroutineScope(Dispatchers.VT + SupervisorJob())

    @PreDestroy
    fun close() {
        embeddingScope.cancel()
    }

    fun create(): HeartbeatPersistence =
        HeartbeatPersistence(
            jsonlWriter = HeartbeatJsonlWriter(Path.of(KlawPaths.conversations)),
            persistDelivered = { channel, chatId, content ->
                val rowId =
                    messageRepository.saveAndGetRowId(
                        id = UUID.randomUUID().toString(),
                        channel = channel,
                        chatId = chatId,
                        role = "assistant",
                        type = "text",
                        content = content,
                        tokens = approximateTokenCount(content),
                    )
                messageEmbeddingService.embedAsync(
                    rowId,
                    "assistant",
                    "text",
                    content,
                    config.memory.autoRag,
                    embeddingScope,
                )
            },
            pushToGateway = { socketServer.pushToGateway(it) },
        )
}
