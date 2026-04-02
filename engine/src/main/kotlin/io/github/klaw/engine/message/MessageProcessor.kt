package io.github.klaw.engine.message

import io.github.klaw.common.config.ContextConfig
import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.common.protocol.ApprovalResponseMessage
import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.common.protocol.RestartRequestSocketMessage
import io.github.klaw.common.protocol.StreamDeltaSocketMessage
import io.github.klaw.common.protocol.StreamEndSocketMessage
import io.github.klaw.common.registry.ModelRegistry
import io.github.klaw.common.util.approximateTokenCount
import io.github.klaw.engine.command.CommandHandler
import io.github.klaw.engine.context.CompactionRunner
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.ContextResult
import io.github.klaw.engine.context.SenderContext
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.socket.CliCommandDispatcher
import io.github.klaw.engine.socket.EngineSocketServer
import io.github.klaw.engine.socket.SocketMessageHandler
import io.github.klaw.engine.tools.ApprovalService
import io.github.klaw.engine.tools.ShutdownController
import io.github.klaw.engine.tools.ToolExecutor
import io.github.klaw.engine.workspace.ScheduleDeliverContext
import io.github.klaw.engine.workspace.ScheduleDeliverSink
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Provider
import jakarta.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    private val llmRouter: LlmRouter,
    private val toolExecutor: ToolExecutor,
    private val socketServerProvider: Provider<EngineSocketServer>,
    private val commandHandler: CommandHandler,
    private val config: EngineConfig,
    private val messageEmbeddingService: MessageEmbeddingService,
    private val cliCommandDispatcher: CliCommandDispatcher,
    private val approvalService: ApprovalService,
    private val shutdownController: ShutdownController,
    private val compactionRunner: CompactionRunner,
    private val subagentRunRepository: io.github.klaw.engine.tools.SubagentRunRepository,
    private val activeSubagentJobs: io.github.klaw.engine.tools.ActiveSubagentJobs,
) : SocketMessageHandler {
    private val logger = KotlinLogging.logger {}
    private val processingScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val llmLimiter = PriorityLlmLimiter(config.processing.maxConcurrentLlm)
    internal val activeProcessingJobs = ConcurrentHashMap<String, Job>()

    private val debounceBuffer =
        DebounceBuffer(
            debounceMs = config.processing.debounceMs,
            scope = processingScope,
            onFlush = ::processMessages,
            maxEntries = config.processing.maxDebounceEntries,
        )

    fun close() {
        processingScope.cancel()
    }

    override suspend fun handleInbound(message: InboundSocketMessage) {
        val accepted = debounceBuffer.add(message)
        if (!accepted) {
            logger.warn { "Debounce buffer full, dropping message channel=${message.channel} chatId=${message.chatId}" }
            socketServerProvider.get().pushToGateway(
                OutboundSocketMessage(
                    channel = message.channel,
                    chatId = message.chatId,
                    content = "The system is under high load. Please try again later.",
                ),
            )
        }
    }

    override suspend fun handleCommand(message: CommandSocketMessage) {
        logger.debug { "Command: chatId=${message.chatId} command=${message.command}" }
        if (message.command == "new") {
            val activeJob = activeProcessingJobs[message.chatId]
            if (activeJob != null) {
                logger.debug {
                    "Cancelling active processing job for chatId=${message.chatId} before /new"
                }
                activeJob.cancel()
                activeJob.join()
            }
        }
        val session = sessionManager.getOrCreate(message.chatId, config.routing.default)
        val result = commandHandler.handle(message, session)
        socketServerProvider.get().pushToGateway(
            OutboundSocketMessage(channel = message.channel, chatId = message.chatId, content = result),
        )
    }

    override suspend fun handleCliRequest(request: CliRequestMessage): String = cliCommandDispatcher.dispatch(request)

    override fun handleApprovalResponse(message: ApprovalResponseMessage) {
        approvalService.handleResponse(message)
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod", "CyclomaticComplexMethod")
    fun handleScheduledMessage(message: ScheduledMessage): Job {
        val model = message.model ?: config.routing.tasks.subagent
        val chatId =
            if (message.runId != null) {
                "subagent:${message.name}:${message.runId}"
            } else {
                "subagent:${message.name}"
            }

        return processingScope.launch {
            if (message.runId != null) {
                activeSubagentJobs.jobs[message.runId] = kotlin.coroutines.coroutineContext[Job]!!
            }
            val startTimeMs = System.currentTimeMillis()
            try {
                withTimeout(config.processing.subagentTimeoutMs) {
                    llmLimiter.withSubagentPermit {
                        try {
                            val session = sessionManager.getOrCreate(chatId, model)
                            if (message.model != null) {
                                sessionManager.updateModel(chatId, message.model)
                            }

                            val sink = if (message.injectInto != null) ScheduleDeliverSink() else null
                            val contextResult =
                                contextBuilder.buildContext(
                                    session,
                                    listOf(message.message),
                                    isSubagent = true,
                                    taskName = message.name,
                                    includeScheduleDeliver = sink != null,
                                    includeSendMessage = false,
                                )
                            logger.debug {
                                "Subagent context built: name=${message.name}, contextMsgCount=${contextResult.messages.size}"
                            }
                            val tools = contextResult.tools

                            // Persist scheduled user message before LLM call
                            if (config.logging.subagentConversations) {
                                val userRowId =
                                    messageRepository.saveAndGetRowId(
                                        id = UUID.randomUUID().toString(),
                                        channel = "scheduler",
                                        chatId = chatId,
                                        role = "user",
                                        type = "text",
                                        content = message.message,
                                        tokens = approximateTokenCount(message.message),
                                    )
                                messageEmbeddingService.embedAsync(
                                    userRowId,
                                    "user",
                                    "text",
                                    message.message,
                                    config.memory.autoRag,
                                    processingScope,
                                )
                            }

                            val scheduledModelContextLimit = ModelRegistry.contextLength(model) ?: 0
                            val runner =
                                ToolCallLoopRunner(
                                    llmRouter,
                                    toolExecutor,
                                    config.processing.maxToolCallRounds,
                                    channel = message.channel,
                                    chatId = message.injectInto,
                                    maxToolOutputChars = config.processing.maxToolOutputChars,
                                    modelContextLimit = scheduledModelContextLimit,
                                )
                            val response =
                                if (sink != null) {
                                    withContext(ScheduleDeliverContext(sink)) {
                                        runner.run(contextResult.messages.toMutableList(), session, tools)
                                    }
                                } else {
                                    runner.run(contextResult.messages.toMutableList(), session, tools)
                                }
                            val content = response.content ?: ""

                            // Persist assistant response
                            val subagentAssistantTokens =
                                response.usage?.completionTokens ?: approximateTokenCount(content)
                            if (config.logging.subagentConversations) {
                                val assistantRowId =
                                    messageRepository.saveAndGetRowId(
                                        id = UUID.randomUUID().toString(),
                                        channel = "scheduler",
                                        chatId = chatId,
                                        role = "assistant",
                                        type = "text",
                                        content = content,
                                        tokens = subagentAssistantTokens,
                                    )
                                messageEmbeddingService.embedAsync(
                                    assistantRowId,
                                    "assistant",
                                    "text",
                                    content,
                                    config.memory.autoRag,
                                    processingScope,
                                )
                            }

                            deliverScheduledResult(sink, message)

                            if (message.runId != null) {
                                val durationMs = System.currentTimeMillis() - startTimeMs
                                subagentRunRepository.completeRun(
                                    message.runId,
                                    lastResponse = content,
                                    deliveredResult = sink?.lastDeliveredMessage,
                                )
                                logger.info {
                                    "Subagent completed: name=${message.name}, runId=${message.runId}, durationMs=$durationMs"
                                }
                                logger.trace { "Subagent DB status update: runId=${message.runId}, status=COMPLETED" }
                                notifySourceSession(message, "completed")
                            }
                        } catch (e: TimeoutCancellationException) {
                            throw e
                        } catch (e: CancellationException) {
                            if (message.runId != null) {
                                subagentRunRepository.cancelRun(message.runId)
                                logger.info { "Subagent cancelled: name=${message.name}, runId=${message.runId}" }
                            }
                            throw e
                        } catch (e: Exception) {
                            logger.error(e) { "Subagent '${message.name}' failed" }
                            if (message.runId != null) {
                                val errorInfo = buildErrorInfo(e)
                                subagentRunRepository.failRun(message.runId, errorInfo)
                                logger.info {
                                    "Subagent failed: name=${message.name}, runId=${message.runId}, error=${e::class.simpleName}"
                                }
                                notifySourceSession(message, "failed: $errorInfo")
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn(e) { "Subagent timed out: name=${message.name}" }
                if (message.runId != null) {
                    subagentRunRepository.failRun(
                        message.runId,
                        "Timeout after ${config.processing.subagentTimeoutMs}ms",
                    )
                    notifySourceSession(message, "timed out")
                }
            } finally {
                if (message.runId != null) {
                    activeSubagentJobs.jobs.remove(message.runId)
                }
            }
        }
    }

    private suspend fun notifySourceSession(
        message: ScheduledMessage,
        statusText: String,
    ) {
        val chatId = message.sourceChatId ?: return
        val channel = message.sourceChannel ?: return
        try {
            socketServerProvider.get().pushToGateway(
                OutboundSocketMessage(
                    channel = channel,
                    chatId = chatId,
                    content = "[Subagent '${message.name}' $statusText]",
                ),
            )
            logger.trace { "Subagent push notification sent: target=$chatId" }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.debug { "Failed to send subagent notification: ${e::class.simpleName}" }
        }
    }

    private fun buildErrorInfo(e: Exception): String =
        when (e) {
            is io.github.klaw.common.error.KlawError.ProviderError -> {
                "ProviderError(status=${e.statusCode})"
            }

            is io.github.klaw.common.error.KlawError.AllProvidersFailedError -> {
                "AllProvidersFailedError"
            }

            is io.github.klaw.common.error.KlawError.ContextLengthExceededError -> {
                "ContextLengthExceededError"
            }

            else -> {
                e::class.simpleName ?: "Unknown"
            }
        }

    private suspend fun deliverScheduledResult(
        sink: ScheduleDeliverSink?,
        message: ScheduledMessage,
    ) {
        val deliveryMessage = sink?.consumeMessage() ?: return
        socketServerProvider.get().pushToGateway(
            OutboundSocketMessage(
                channel = message.channel ?: "engine",
                chatId = message.injectInto!!,
                content = deliveryMessage,
            ),
        )
        // Record in interactive session so user can follow up naturally
        val injectedRowId =
            messageRepository.saveAndGetRowId(
                id = UUID.randomUUID().toString(),
                channel = message.channel ?: "engine",
                chatId = message.injectInto,
                role = "assistant",
                type = "text",
                content = deliveryMessage,
                tokens = approximateTokenCount(deliveryMessage),
            )
        messageEmbeddingService.embedAsync(
            injectedRowId,
            "assistant",
            "text",
            deliveryMessage,
            config.memory.autoRag,
            processingScope,
        )
    }

    @Suppress("TooGenericExceptionCaught", "LongMethod")
    private suspend fun processMessages(messages: List<InboundSocketMessage>) {
        if (messages.isEmpty()) return
        val first = messages.first()
        val chatId = first.chatId
        val channel = first.channel
        logger.debug { "Processing: chatId=$chatId channel=$channel msgCount=${messages.size}" }

        val currentJob = kotlin.coroutines.coroutineContext[Job]!!
        activeProcessingJobs[chatId] = currentJob
        try {
            llmLimiter.withInteractivePermit {
                var session: Session? = null
                var contextResult: ContextResult? = null
                try {
                    session = sessionManager.getOrCreate(chatId, config.routing.default)
                    val pendingTexts = messages.map { it.content }

                    // Persist user messages before building context so sumTokensInSegment is accurate
                    persistInboundMessages(messages)

                    val senderContext =
                        SenderContext(
                            senderId = first.senderId,
                            senderName = first.senderName,
                            chatType = first.chatType,
                            platform = first.channel,
                            chatTitle = first.chatTitle,
                            messageId = first.messageId,
                        )
                    contextResult =
                        contextBuilder.buildContext(
                            session,
                            pendingTexts,
                            isSubagent = false,
                            senderContext = senderContext,
                        )
                    logger.debug {
                        "Context built: chatId=$chatId contextMsgs=${contextResult.messages.size}" +
                            " uncoveredTokens=${contextResult.uncoveredMessageTokens}"
                    }
                    val tools = contextResult.tools

                    val registryContextLength = ModelRegistry.contextLength(session.model)
                    val rawBudget =
                        config.context.tokenBudget
                            ?: registryContextLength
                            ?: ContextConfig.FALLBACK_BUDGET_TOKENS
                    val modelContextLimit = registryContextLength ?: 0
                    val streamCtx = buildStreamContext(channel, chatId)

                    val runner =
                        ToolCallLoopRunner(
                            llmRouter,
                            toolExecutor,
                            config.processing.maxToolCallRounds,
                            messageRepository,
                            channel,
                            chatId,
                            contextBudgetTokens = rawBudget,
                            maxToolOutputChars = config.processing.maxToolOutputChars,
                            modelContextLimit = modelContextLimit,
                            streamingEnabled = streamCtx != null,
                            onDelta = streamCtx?.onDelta,
                        )
                    val response = runner.run(contextResult.messages.toMutableList(), session, tools)
                    logger.debug {
                        "LLM responded: chatId=$chatId contentLen=${response.content?.length ?: 0}" +
                            " tokens=${response.usage?.totalTokens}"
                    }

                    correctUserMessageTokens(runner.firstPromptTokens, contextResult.messages, messages)

                    val content = persistAssistantResponse(response, channel, chatId)
                    val deliveryContent = appendStopNoticeIfNeeded(content, response)

                    deliverResponse(deliveryContent, channel, chatId, streamCtx)

                    // Fire-and-forget background compaction
                    val successSegmentStart = session.segmentStart
                    val successUncoveredTokens = contextResult.uncoveredMessageTokens
                    val successBudget = contextResult.budget
                    processingScope.launch {
                        try {
                            compactionRunner.runIfNeeded(
                                chatId = chatId,
                                segmentStart = successSegmentStart,
                                uncoveredMessageTokens = successUncoveredTokens,
                                budget = successBudget,
                            )
                        } catch (
                            @Suppress("TooGenericExceptionCaught") e: Exception,
                        ) {
                            logger.error(e) { "Background compaction failed" }
                        }
                    }
                } catch (e: KlawError) {
                    logger.warn { "Processing failed: chatId=$chatId error=${e::class.simpleName}" }
                    socketServerProvider.get().pushToGateway(
                        OutboundSocketMessage(
                            channel = channel,
                            chatId = chatId,
                            content = userFacingMessage(e),
                        ),
                    )
                    // Trigger compaction on failure — prevents context growth deadlock when LLM returns 500
                    val errorCr = contextResult
                    val errorSeg = session?.segmentStart
                    if (errorCr != null && errorSeg != null) {
                        val capturedUncoveredTokens = errorCr.uncoveredMessageTokens
                        val capturedBudget = errorCr.budget
                        processingScope.launch {
                            try {
                                compactionRunner.runIfNeeded(
                                    chatId = chatId,
                                    segmentStart = errorSeg,
                                    uncoveredMessageTokens = capturedUncoveredTokens,
                                    budget = capturedBudget,
                                )
                            } catch (
                                @Suppress("TooGenericExceptionCaught") ex: Exception,
                            ) {
                                logger.error(ex) { "Background compaction failed" }
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    logger.error(e) { "Unexpected error processing chatId=$chatId" }
                    socketServerProvider.get().pushToGateway(
                        OutboundSocketMessage(
                            channel = channel,
                            chatId = chatId,
                            content = "An internal error occurred.",
                        ),
                    )
                }
            }
        } finally {
            activeProcessingJobs.remove(chatId, currentJob)
            executeDeferredRestarts()
        }
    }

    /**
     * Holds streaming context for a single interactive message processing turn.
     * Created only when streaming is enabled in config.
     */
    private class StreamContext(
        val streamId: String,
        val onDelta: suspend (String) -> Unit,
    )

    private fun buildStreamContext(
        channel: String,
        chatId: String,
    ): StreamContext? {
        if (!config.processing.streaming.enabled) return null
        val streamId = UUID.randomUUID().toString()
        return StreamContext(streamId) { delta ->
            socketServerProvider.get().pushMessage(
                StreamDeltaSocketMessage(
                    channel = channel,
                    chatId = chatId,
                    delta = delta,
                    streamId = streamId,
                ),
            )
        }
    }

    private suspend fun deliverResponse(
        content: String,
        channel: String,
        chatId: String,
        streamCtx: StreamContext?,
    ) {
        if (isSilent(content)) return
        if (streamCtx != null) {
            socketServerProvider.get().pushMessage(
                StreamEndSocketMessage(
                    channel = channel,
                    chatId = chatId,
                    streamId = streamCtx.streamId,
                    fullContent = content,
                ),
            )
        } else {
            socketServerProvider.get().pushToGateway(
                OutboundSocketMessage(channel = channel, chatId = chatId, content = content),
            )
        }
    }

    private suspend fun persistAssistantResponse(
        response: io.github.klaw.common.llm.LlmResponse,
        channel: String,
        chatId: String,
    ): String {
        val content = response.content ?: ""
        val assistantTokens = response.usage?.completionTokens ?: approximateTokenCount(content)
        val assistantRowId =
            messageRepository.saveAndGetRowId(
                id = UUID.randomUUID().toString(),
                channel = channel,
                chatId = chatId,
                role = "assistant",
                type = "text",
                content = content,
                tokens = assistantTokens,
            )
        messageEmbeddingService.embedAsync(
            assistantRowId,
            "assistant",
            "text",
            content,
            config.memory.autoRag,
            processingScope,
        )
        return content
    }

    private suspend fun persistInboundMessages(messages: List<InboundSocketMessage>) {
        messages.forEach { msg ->
            val hasAttachments = msg.attachments.isNotEmpty()
            val type = if (hasAttachments) "multimodal" else "text"
            logger.debug { "persistInbound: chatId=${msg.chatId} type=$type attachments=${msg.attachments.size}" }
            val metadata =
                if (hasAttachments) {
                    kotlinx.serialization.json.Json.encodeToString(
                        AttachmentMetadata.serializer(),
                        AttachmentMetadata(
                            attachments = msg.attachments.map { AttachmentRef(it.path, it.mimeType) },
                        ),
                    )
                } else {
                    null
                }

            val rowId =
                messageRepository.saveAndGetRowId(
                    id = msg.id,
                    channel = msg.channel,
                    chatId = msg.chatId,
                    role = "user",
                    type = type,
                    content = msg.content,
                    metadata = metadata,
                    tokens = approximateTokenCount(msg.content),
                )
            messageEmbeddingService.embedAsync(
                rowId,
                "user",
                type,
                msg.content,
                config.memory.autoRag,
                processingScope,
            )
        }
    }

    /**
     * Corrects user message token counts using the API-reported [firstPromptTokens].
     *
     * The first LLM call's promptTokens covers the entire context sent (system + history + pending).
     * By subtracting approximate tokens for non-pending messages, we get the actual total for
     * pending user messages, then distribute proportionally.
     */
    private suspend fun correctUserMessageTokens(
        firstPromptTokens: Int?,
        contextMessages: List<io.github.klaw.common.llm.LlmMessage>,
        userMessages: List<InboundSocketMessage>,
    ) {
        if (firstPromptTokens == null || userMessages.isEmpty()) return

        val pendingCount = userMessages.size
        val nonPendingMessages = contextMessages.dropLast(pendingCount)
        val nonPendingApproxTokens = nonPendingMessages.sumOf { ToolCallLoopRunner.approximateMessageTokens(it) }
        val actualPendingTotal = firstPromptTokens - nonPendingApproxTokens

        if (actualPendingTotal <= 0) return

        if (userMessages.size == 1) {
            messageRepository.updateTokens(userMessages[0].id, actualPendingTotal)
            return
        }

        val approxTokens = userMessages.map { approximateTokenCount(it.content) }
        val approxTotal = approxTokens.sum()
        if (approxTotal <= 0) return

        val remainder = actualPendingTotal % userMessages.size
        userMessages.forEachIndexed { i, msg ->
            val proportional = (approxTokens[i].toLong() * actualPendingTotal / approxTotal).toInt()
            val adjusted = proportional + if (i < remainder && proportional == 0) 1 else 0
            messageRepository.updateTokens(msg.id, adjusted)
        }
    }

    private suspend fun executeDeferredRestarts() {
        if (shutdownController.consumePendingGatewayRestart()) {
            logger.debug { "Executing deferred gateway restart" }
            socketServerProvider.get().pushMessage(RestartRequestSocketMessage)
        }
        shutdownController.executePendingRestart()
    }

    companion object {
        internal fun userFacingMessage(error: KlawError): String =
            when (error) {
                is KlawError.ProviderError -> {
                    if (error.statusCode == null) {
                        "LLM service is unreachable. Please try again later."
                    } else {
                        "LLM returned an error. Please try again later."
                    }
                }

                is KlawError.AllProvidersFailedError -> {
                    "All LLM providers are unreachable. Please try again later."
                }

                is KlawError.ContextLengthExceededError -> {
                    "Message too long for the model's context window. Try /new to start a fresh session."
                }

                is KlawError.ToolCallError -> {
                    "Sorry, something went wrong."
                }
            }
    }
}
