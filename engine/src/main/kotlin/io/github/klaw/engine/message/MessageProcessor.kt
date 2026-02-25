package io.github.klaw.engine.message

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.command.CommandHandler
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.socket.SocketMessageHandler
import io.github.klaw.engine.tools.ToolExecutor
import jakarta.annotation.PreDestroy
import jakarta.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.UUID

/**
 * Central message processing pipeline for the Klaw engine.
 *
 * Orchestrates: inbound message debouncing → context assembly → LLM call →
 * tool call loop → response delivery to gateway.
 *
 * Rate-limited by [llmSemaphore] (maxConcurrentLlm permits).
 */
@Singleton
@Suppress("LongParameterList")
class MessageProcessor(
    private val sessionManager: SessionManager,
    private val messageRepository: MessageRepository,
    private val contextBuilder: ContextBuilder,
    private val toolRegistry: ToolRegistry,
    private val llmRouter: LlmRouter,
    private val toolExecutor: ToolExecutor,
    private val socketServer: EngineSocketServer,
    private val commandHandler: CommandHandler,
    private val config: EngineConfig,
) : SocketMessageHandler {
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val llmSemaphore = Semaphore(config.processing.maxConcurrentLlm)

    private val debounceBuffer =
        DebounceBuffer(
            debounceMs = config.processing.debounceMs,
            scope = processingScope,
            onFlush = ::processMessages,
        )

    @PreDestroy
    fun close() {
        processingScope.cancel()
    }

    override suspend fun handleInbound(message: InboundSocketMessage) {
        debounceBuffer.add(message)
    }

    override suspend fun handleCommand(message: CommandSocketMessage) {
        val session = sessionManager.getOrCreate(message.chatId, config.routing.default)
        val result = commandHandler.handle(message, session)
        socketServer.pushToGateway(
            OutboundSocketMessage(channel = message.channel, chatId = message.chatId, content = result),
        )
    }

    override suspend fun handleCliRequest(request: CliRequestMessage): String = """{"status":"ok","engine":"klaw"}"""

    @Suppress("TooGenericExceptionCaught")
    private suspend fun processMessages(messages: List<InboundSocketMessage>) {
        if (messages.isEmpty()) return
        val first = messages.first()
        val chatId = first.chatId
        val channel = first.channel

        llmSemaphore.withPermit {
            try {
                val session = sessionManager.getOrCreate(chatId, config.routing.default)
                val tools: List<ToolDef> = toolRegistry.listTools()
                val pendingTexts = messages.map { it.content }
                val context = contextBuilder.buildContext(session, pendingTexts, isSubagent = false)

                // Persist user messages to DB before LLM call so history is intact on crash
                messages.forEach { msg ->
                    messageRepository.save(
                        id = msg.id,
                        channel = msg.channel,
                        chatId = msg.chatId,
                        role = "user",
                        type = "text",
                        content = msg.content,
                    )
                }

                val runner = ToolCallLoopRunner(llmRouter, toolExecutor, config.processing.maxToolCallRounds)
                val response = runner.run(context.toMutableList(), session, tools)

                // Persist assistant response to DB
                val content = response.content ?: ""
                messageRepository.save(
                    id = UUID.randomUUID().toString(),
                    channel = channel,
                    chatId = chatId,
                    role = "assistant",
                    type = "text",
                    content = content,
                )

                if (!isSilent(content)) {
                    socketServer.pushToGateway(
                        OutboundSocketMessage(channel = channel, chatId = chatId, content = content),
                    )
                }
            } catch (_: KlawError.ToolCallLoopException) {
                socketServer.pushToGateway(
                    OutboundSocketMessage(
                        channel = channel,
                        chatId = chatId,
                        content = "Sorry, I reached the tool call limit. Please try again.",
                    ),
                )
            } catch (_: KlawError) {
                socketServer.pushToGateway(
                    OutboundSocketMessage(channel = channel, chatId = chatId, content = "Sorry, something went wrong."),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                socketServer.pushToGateway(
                    OutboundSocketMessage(channel = channel, chatId = chatId, content = "An internal error occurred."),
                )
            }
        }
    }
}
