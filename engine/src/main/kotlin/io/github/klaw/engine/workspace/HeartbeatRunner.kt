package io.github.klaw.engine.workspace

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.FinishReason
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.common.llm.ToolResult
import io.github.klaw.common.protocol.OutboundSocketMessage
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.context.WorkspaceLoader
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.tools.ToolExecutor
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.atomic.AtomicBoolean

private val logger = KotlinLogging.logger {}

@Suppress("LongParameterList")
class HeartbeatRunner(
    private val config: EngineConfig,
    private val chat: suspend (LlmRequest, String) -> LlmResponse,
    private val toolExecutor: ToolExecutor,
    private val getOrCreateSession: suspend (chatId: String, defaultModel: String) -> Session,
    private val workspaceLoader: WorkspaceLoader,
    private val toolRegistry: ToolRegistry,
    private val pushToGateway: suspend (OutboundSocketMessage) -> Unit,
    private val workspacePath: Path,
    private val maxToolCallRounds: Int,
) {
    private val running = AtomicBoolean(false)

    @Volatile
    var deliveryChannel: String? = config.heartbeat.channel

    @Volatile
    var deliveryChatId: String? = config.heartbeat.injectInto

    fun acquireRunLock(): Boolean = running.compareAndSet(false, true)

    fun releaseRunLock() {
        running.set(false)
    }

    @Suppress("TooGenericExceptionCaught")
    fun runHeartbeat() {
        if (!running.compareAndSet(false, true)) {
            logger.debug { "Heartbeat run skipped — previous still running" }
            return
        }
        try {
            runBlocking { doExecute() }
        } catch (e: Exception) {
            logger.error(e) { "Heartbeat execution failed" }
        } finally {
            running.set(false)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun executeHeartbeat() {
        if (!running.compareAndSet(false, true)) {
            logger.debug { "Heartbeat run skipped — previous still running" }
            return
        }
        try {
            doExecute()
        } catch (e: Exception) {
            logger.error(e) { "Heartbeat execution failed" }
        } finally {
            running.set(false)
        }
    }

    @Suppress("ReturnCount")
    private suspend fun doExecute() {
        val channel = deliveryChannel
        val chatId = deliveryChatId
        if (channel == null || chatId == null) {
            logger.debug { "Heartbeat delivery target not configured — skipping" }
            return
        }

        val heartbeatPath = workspacePath.resolve("HEARTBEAT.md")
        if (!Files.exists(heartbeatPath)) {
            logger.debug { "No HEARTBEAT.md found — skipping heartbeat" }
            return
        }
        val content = Files.readString(heartbeatPath)
        if (content.isBlank()) {
            logger.debug { "HEARTBEAT.md is empty — skipping heartbeat" }
            return
        }
        val model = config.heartbeat.model ?: config.routing.default
        val session = getOrCreateSession("heartbeat", model)

        val systemPrompt = workspaceLoader.loadSystemPrompt()
        val tools =
            toolRegistry.listTools(
                includeSkillList = false,
                includeSkillLoad = false,
                includeHeartbeatDeliver = true,
                includeSendMessage = false,
            )

        val fullSystemPrompt =
            systemPrompt + HEARTBEAT_SYSTEM_SUFFIX

        val context =
            mutableListOf(
                LlmMessage(role = "system", content = fullSystemPrompt),
                LlmMessage(role = "user", content = content),
            )

        val sink = HeartbeatDeliverSink()

        runToolLoop(context, session.model, tools, sink)

        val deliveryMessage = sink.consumeMessage()
        if (deliveryMessage != null) {
            logger.debug { "Heartbeat delivering message to $channel/$chatId" }
            pushToGateway(OutboundSocketMessage(channel = channel, chatId = chatId, content = deliveryMessage))
        } else {
            logger.debug { "Heartbeat completed without delivery" }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runToolLoop(
        context: MutableList<LlmMessage>,
        modelId: String,
        tools: List<ToolDef>,
        sink: HeartbeatDeliverSink,
    ): LlmResponse {
        var rounds = 0
        while (rounds < maxToolCallRounds) {
            val response =
                chat(
                    LlmRequest(messages = context, tools = tools.ifEmpty { null }),
                    modelId,
                )
            rounds++
            if (response.toolCalls.isNullOrEmpty()) return response

            val toolCalls = response.toolCalls!!
            val results = executeToolCalls(toolCalls, sink)

            context.add(LlmMessage(role = "assistant", content = null, toolCalls = toolCalls))
            results.forEach { result ->
                context.add(LlmMessage(role = "tool", content = result.content, toolCallId = result.callId))
            }
        }
        logger.warn { "Heartbeat reached max tool call rounds ($maxToolCallRounds)" }
        return LlmResponse(content = null, toolCalls = null, usage = null, finishReason = FinishReason.STOP)
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeToolCalls(
        toolCalls: List<ToolCall>,
        sink: HeartbeatDeliverSink,
    ): List<ToolResult> =
        try {
            withContext(HeartbeatDeliverContext(sink)) {
                toolExecutor.executeAll(toolCalls)
            }
        } catch (e: Exception) {
            logger.warn(e) { "Heartbeat tool execution failed" }
            // e::class.simpleName in tool result content is intentional and safe (no message text)
            toolCalls.map { ToolResult(callId = it.id, content = "Tool execution failed: ${e::class.simpleName}") }
        }

    companion object {
        @Suppress("MaxLineLength")
        private const val HEARTBEAT_SYSTEM_SUFFIX =
            "\n\n## Heartbeat Run\n\n" +
                "You are running as an autonomous heartbeat. " +
                "You have access to all tools. " +
                "Your task is described in the user message — follow those instructions.\n\n" +
                "When you are done, decide whether the user needs to know anything. " +
                "If you found something noteworthy or actionable, call the `heartbeat_deliver` tool — " +
                "this is the ONLY way to communicate with the user; your response text is never seen. " +
                "If nothing interesting happened, simply finish without calling it. " +
                "Not every heartbeat needs to produce a message — only deliver when there is real value.\n"

        fun parseInterval(value: String): Duration? {
            if (value.isBlank() || value.equals("off", ignoreCase = true)) return null
            return try {
                Duration.parse(value)
            } catch (_: Exception) {
                logger.warn { "Invalid heartbeat interval: length=${value.length}" }
                null
            }
        }
    }
}
