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
import jakarta.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
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
    private val log = LoggerFactory.getLogger(MessageProcessor::class.java)
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val llmLimiter = PriorityLlmLimiter(config.processing.maxConcurrentLlm)

    private val debounceBuffer =
        DebounceBuffer(
            debounceMs = config.processing.debounceMs,
            scope = processingScope,
            onFlush = ::processMessages,
        )

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
    fun handleScheduledMessage(message: ScheduledMessage): Job {
        val model = message.model ?: config.routing.tasks.subagent
        val chatId = "subagent:${message.name}"

        return processingScope.launch {
            llmLimiter.withSubagentPermit {
                try {
                    val session = sessionManager.getOrCreate(chatId, model)
                    if (message.model != null) {
                        sessionManager.updateModel(chatId, message.model)
                    }

                    val tools = toolRegistry.listTools()
                    val context = contextBuilder.buildContext(session, listOf(message.message), isSubagent = true)

                    // Persist scheduled user message before LLM call
                    if (config.logging.subagentConversations) {
                        messageRepository.save(
                            id = UUID.randomUUID().toString(),
                            channel = "scheduler",
                            chatId = chatId,
                            role = "user",
                            type = "text",
                            content = message.message,
                        )
                    }

                    val runner =
                        ToolCallLoopRunner(
                            llmRouter,
                            toolExecutor,
                            config.processing.maxToolCallRounds,
                        )
                    val response = runner.run(context.toMutableList(), session, tools)
                    val content = response.content ?: ""

                    // Persist assistant response
                    if (config.logging.subagentConversations) {
                        messageRepository.save(
                            id = UUID.randomUUID().toString(),
                            channel = "scheduler",
                            chatId = chatId,
                            role = "assistant",
                            type = "text",
                            content = content,
                        )
                    }

                    if (!isSilent(content) && message.injectInto != null) {
                        socketServer.pushToGateway(
                            OutboundSocketMessage(
                                channel = "engine",
                                chatId = message.injectInto,
                                content = content,
                            ),
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log.error("Subagent '{}' failed: {}", message.name, e.message, e)
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    private suspend fun processMessages(messages: List<InboundSocketMessage>) {
        if (messages.isEmpty()) return
        val first = messages.first()
        val chatId = first.chatId
        val channel = first.channel

        llmLimiter.withInteractivePermit {
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

                val contextBudget =
                    config.models[session.model]?.contextBudget
                        ?: config.context.defaultBudgetTokens
                val runner =
                    ToolCallLoopRunner(
                        llmRouter,
                        toolExecutor,
                        config.processing.maxToolCallRounds,
                        messageRepository,
                        channel,
                        chatId,
                        contextBudgetTokens = contextBudget,
                    )
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
