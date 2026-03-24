package io.github.klaw.engine.context

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.VisionConfig
import io.github.klaw.common.llm.ImageUrlContentPart
import io.github.klaw.common.llm.ImageUrlData
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.TextContentPart
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.registry.ModelRegistry
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.memory.AutoRagResult
import io.github.klaw.engine.memory.AutoRagService
import io.github.klaw.engine.message.AttachmentMetadata
import io.github.klaw.engine.message.MessageRepository
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.tools.EngineHealthProvider
import io.github.klaw.engine.tools.ImageAnalyzeTool
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Provider
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlin.coroutines.cancellation.CancellationException

data class SenderContext(
    val senderId: String?,
    val senderName: String?,
    val chatType: String?,
    val platform: String?,
    val chatTitle: String?,
    val messageId: String?,
)

data class ContextResult(
    val messages: List<LlmMessage>,
    val includeSkillList: Boolean,
    val includeSkillLoad: Boolean,
    val uncoveredMessageTokens: Long = 0,
    val budget: Int = 0,
)

private val logger = KotlinLogging.logger {}

private val metadataJson = Json { ignoreUnknownKeys = true }

@Singleton
@Suppress("LongParameterList")
class ContextBuilder(
    private val workspaceLoader: WorkspaceLoader,
    private val messageRepository: MessageRepository,
    private val summaryService: SummaryService,
    private val skillRegistry: SkillRegistry,
    private val toolRegistry: ToolRegistry,
    private val config: EngineConfig,
    private val autoRagService: AutoRagService,
    private val subagentHistoryLoader: SubagentHistoryLoader,
    private val healthProviderLazy: Provider<EngineHealthProvider>,
    private val llmRouter: LlmRouter,
) {
    /**
     * Directories from which the engine is allowed to read image attachments.
     * Initialized from config by default. Tests override via [overrideAllowedImageDirs].
     */
    private var allowedImageDirs: List<Path> =
        buildList {
            add(Path.of(KlawPaths.workspace))
            val attachDir = config.vision.attachmentsDirectory
            if (attachDir.isNotBlank()) add(Path.of(attachDir))
        }.also { dirs ->
            logger.debug { "allowedImageDirs: ${dirs.map { it.toAbsolutePath() }}" }
        }

    /** Visible for testing — allows tests to set custom allowed dirs. */
    internal fun overrideAllowedImageDirs(dirs: List<Path>) {
        allowedImageDirs = dirs
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    suspend fun buildContext(
        session: Session,
        pendingMessages: List<String>,
        isSubagent: Boolean,
        taskName: String? = null,
        senderContext: SenderContext? = null,
    ): ContextResult {
        skillRegistry.discover()
        val systemPrompt = workspaceLoader.loadSystemPrompt()

        val allSkills = skillRegistry.listAll()
        val skillCount = allSkills.size
        val inlineSkills = skillCount in 1..config.skills.maxInlineSkills
        val includeSkillList = skillCount > config.skills.maxInlineSkills
        val includeSkillLoad = skillCount > 0

        val tools =
            toolRegistry.listTools(
                includeSkillList = includeSkillList,
                includeSkillLoad = includeSkillLoad,
            )

        val toolDescriptions =
            buildString {
                if (tools.isNotEmpty()) append(tools.joinToString("\n") { it.description })
            }

        val inlineSkillSection =
            if (inlineSkills) {
                allSkills.joinToString("\n") { "- ${it.name}: ${it.description}" }
            } else {
                ""
            }

        // Subagent early-return: uses SubagentHistoryLoader, no DB sliding window, no auto-RAG
        if (isSubagent && taskName != null) {
            val systemContent = buildSystemContent(systemPrompt, toolDescriptions, inlineSkillSection, skillCount, null)
            val scheduledSystemContent =
                buildString {
                    append(systemContent)
                    append("\n\n## Scheduled Task Execution\n")
                    append("You are running as scheduled task '$taskName'. ")
                    append("Execute the instruction in the user message. ")
                    append("If you have a result to deliver to the user, call `schedule_deliver` — ")
                    append("this is the only delivery mechanism. ")
                    append("Do NOT use `send_message` to deliver results or confirmations. ")
                    append("If there is nothing to deliver, complete without calling it.")
                }
            val historyMessages = subagentHistoryLoader.loadHistory(taskName, config.context.subagentHistory)
            return ContextResult(
                buildSubagentContext(scheduledSystemContent, historyMessages, pendingMessages),
                includeSkillList = includeSkillList,
                includeSkillLoad = includeSkillLoad,
            )
        }

        val systemContent =
            buildSystemContent(systemPrompt, toolDescriptions, inlineSkillSection, skillCount, senderContext)

        val budgetTokens =
            ModelRegistry.contextLength(session.model)
                ?: config.context.defaultBudgetTokens
        // Summarization: compute summary budget, fetch summaries with coverage info
        val summaryResult: SummaryContextResult
        if (config.memory.compaction.enabled) {
            val summaryBudget = (budgetTokens * config.memory.compaction.summaryBudgetFraction).toInt()
            summaryResult = summaryService.getSummariesForContext(session.chatId, summaryBudget, session.segmentStart)
        } else {
            summaryResult = SummaryContextResult(emptyList(), null, false)
        }

        // Fetch messages: coverage-based (no budget trimming — zero gap guarantee)
        val dbMessages =
            if (summaryResult.coverageEnd != null) {
                messageRepository.getUncoveredMessages(session.chatId, session.segmentStart, summaryResult.coverageEnd)
            } else {
                messageRepository.getAllMessagesInSegment(session.chatId, session.segmentStart)
            }

        val uncoveredMessageTokens = dbMessages.sumOf { it.tokens.toLong() }

        // Auto-RAG guard: triggers when summaries have been evicted (model lost summarized access)
        val autoRagResults: List<AutoRagResult> =
            if (
                !isSubagent &&
                config.memory.autoRag.enabled &&
                summaryResult.hasEvictedSummaries
            ) {
                val windowRowIds = dbMessages.map { it.rowId }.toSet()
                val userQuery = pendingMessages.joinToString(" ")
                autoRagService.search(
                    userQuery,
                    session.chatId,
                    session.segmentStart,
                    windowRowIds,
                    config.memory.autoRag,
                )
            } else {
                emptyList()
            }

        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage(role = "system", content = systemContent))

        // Summaries injected as a second system message, oldest first (chronological)
        if (summaryResult.summaries.isNotEmpty()) {
            val summaryContent =
                buildString {
                    append("## Conversation Summaries\n\n")
                    summaryResult.summaries.forEachIndexed { index, summary ->
                        if (index > 0) append("\n\n---\n\n")
                        append(summary.content)
                    }
                }
            messages.add(LlmMessage(role = "system", content = summaryContent))
        }

        // Auto-RAG block inserted after summaries
        if (autoRagResults.isNotEmpty()) {
            val autoRagContent =
                buildString {
                    append("From earlier in this conversation:\n\n")
                    autoRagResults.forEach { result ->
                        append("[${result.role}] ${result.content}\n")
                    }
                }.trim()
            messages.add(LlmMessage(role = "system", content = autoRagContent))
        }

        val visionCapable = ModelRegistry.supportsImage(session.model)
        for (msg in dbMessages) {
            if (msg.role == "session_break") continue
            if (msg.type == "multimodal" && msg.metadata != null) {
                val llmMsg =
                    buildMultimodalLlmMessage(
                        msg,
                        visionCapable,
                        config.vision,
                    )
                messages.add(llmMsg)
            } else {
                messages.add(LlmMessage(role = msg.role, content = msg.content))
            }
        }

        pendingMessages.forEach { content ->
            messages.add(LlmMessage(role = "user", content = content))
        }

        return ContextResult(
            messages = messages,
            includeSkillList = includeSkillList,
            includeSkillLoad = includeSkillLoad,
            uncoveredMessageTokens = uncoveredMessageTokens,
            budget = budgetTokens,
        )
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun buildMultimodalLlmMessage(
        msg: MessageRepository.MessageRow,
        visionCapable: Boolean,
        visionConfig: VisionConfig,
    ): LlmMessage {
        val attachmentMeta =
            parseAttachmentMetadata(msg.metadata!!) ?: return LlmMessage(
                role = msg.role,
                content = msg.content,
            )

        val limit = visionConfig.maxImagesPerMessage
        val limitedAttachments = attachmentMeta.attachments.take(limit)

        if (visionCapable) {
            return buildInlineImageMessage(msg.content, msg.role, limitedAttachments)
        }

        if (visionConfig.model.isNotBlank()) {
            val description = getOrAutoDescribe(msg, attachmentMeta, limitedAttachments, visionConfig)
            val enrichedContent =
                if (description.isNotBlank()) {
                    "[Image descriptions: $description]\n\n${msg.content}"
                } else {
                    msg.content
                }
            return LlmMessage(role = msg.role, content = enrichedContent)
        }

        logger.warn { "Multimodal message skipped: attachmentCount=${limitedAttachments.size}, no vision model" }
        return LlmMessage(role = msg.role, content = msg.content)
    }

    private suspend fun buildInlineImageMessage(
        content: String,
        role: String,
        attachments: List<io.github.klaw.engine.message.AttachmentRef>,
    ): LlmMessage {
        val parts = mutableListOf<io.github.klaw.common.llm.ContentPart>()
        parts.add(TextContentPart(content))

        for (att in attachments) {
            val dataUrl = readImageAsDataUrl(att.path, att.mimeType)
            if (dataUrl != null) {
                parts.add(ImageUrlContentPart(ImageUrlData(dataUrl)))
            }
        }

        return if (parts.size > 1) {
            LlmMessage(role = role, contentParts = parts)
        } else {
            LlmMessage(role = role, content = content)
        }
    }

    private suspend fun readImageAsDataUrl(
        path: String,
        mimeType: String,
    ): String? =
        try {
            withContext(Dispatchers.VT) {
                val filePath = Path.of(path)
                if (!isPathWithinAllowedDirs(filePath)) {
                    logger.warn { "Image path outside allowed directories: pathLength=${path.length}" }
                    return@withContext null
                }
                if (!Files.exists(filePath)) {
                    logger.debug { "Image file not found: pathLength=${path.length}" }
                    return@withContext null
                }
                val bytes = Files.readAllBytes(filePath)
                val base64 = Base64.getEncoder().encodeToString(bytes)
                "data:$mimeType;base64,$base64"
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            logger.warn { "Failed to read image: ${e::class.simpleName}" }
            null
        } catch (e: SecurityException) {
            logger.warn { "Security error reading image: ${e::class.simpleName}" }
            null
        }

    private fun isPathWithinAllowedDirs(filePath: Path): Boolean {
        val normalized = filePath.normalize()
        val result =
            allowedImageDirs.any { base ->
                val baseNorm = base.normalize()
                val starts = normalized.startsWith(baseNorm)
                val symlink = Files.isSymbolicLink(normalized)
                logger.trace { "pathCheck: normalized=$normalized base=$baseNorm starts=$starts symlink=$symlink" }
                starts && (!symlink || isRealPathWithin(normalized, baseNorm))
            }
        return result
    }

    private fun isRealPathWithin(
        path: Path,
        base: Path,
    ): Boolean =
        try {
            path.toRealPath().startsWith(base.toRealPath())
        } catch (_: IOException) {
            false
        }

    private suspend fun getOrAutoDescribe(
        msg: MessageRepository.MessageRow,
        attachmentMeta: AttachmentMetadata,
        limitedAttachments: List<io.github.klaw.engine.message.AttachmentRef>,
        visionConfig: VisionConfig,
    ): String {
        // Check for cached descriptions
        val cachedDescriptions = attachmentMeta.descriptions
        val uncachedAttachments = limitedAttachments.filter { it.path !in cachedDescriptions }

        if (uncachedAttachments.isEmpty()) {
            return limitedAttachments.mapNotNull { cachedDescriptions[it.path] }.joinToString("\n\n")
        }

        val newDescriptions = autoDescribe(uncachedAttachments, visionConfig)

        // Merge and cache
        val allDescriptions = cachedDescriptions + newDescriptions
        if (newDescriptions.isNotEmpty()) {
            val updatedMeta = attachmentMeta.copy(descriptions = allDescriptions)
            messageRepository.updateMetadata(msg.id, metadataJson.encodeToString(updatedMeta))
        }

        return limitedAttachments.mapNotNull { allDescriptions[it.path] }.joinToString("\n\n")
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun autoDescribe(
        attachments: List<io.github.klaw.engine.message.AttachmentRef>,
        visionConfig: VisionConfig,
    ): Map<String, String> {
        val results = mutableMapOf<String, String>()

        for (att in attachments) {
            val dataUrl = readImageAsDataUrl(att.path, att.mimeType) ?: continue

            try {
                val request =
                    LlmRequest(
                        messages =
                            listOf(
                                LlmMessage(role = "system", content = ImageAnalyzeTool.VISION_SYSTEM_PROMPT),
                                LlmMessage(
                                    role = "user",
                                    contentParts =
                                        listOf(
                                            TextContentPart(ImageAnalyzeTool.DEFAULT_PROMPT),
                                            ImageUrlContentPart(ImageUrlData(dataUrl)),
                                        ),
                                ),
                            ),
                        maxTokens =
                            visionConfig.maxTokens
                                ?: ModelRegistry.maxOutput(visionConfig.model)
                                ?: DEFAULT_VISION_MAX_TOKENS,
                    )

                val response = llmRouter.chat(request, visionConfig.model)
                val description = response.content
                if (!description.isNullOrBlank()) {
                    results[att.path] = description
                }
                logger.debug { "Auto-describe completed: mimeType=${att.mimeType}" }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn { "Auto-describe failed: ${e::class.simpleName}" }
            }
        }

        return results
    }

    private fun parseAttachmentMetadata(metadata: String): AttachmentMetadata? =
        try {
            metadataJson.decodeFromString<AttachmentMetadata>(metadata)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn { "Failed to parse attachment metadata: ${e::class.simpleName}" }
            null
        }

    private fun buildSubagentContext(
        systemContent: String,
        historyMessages: List<LlmMessage>,
        pendingMessages: List<String>,
    ): List<LlmMessage> {
        val messages = mutableListOf<LlmMessage>()
        messages.add(LlmMessage(role = "system", content = systemContent))
        messages.addAll(historyMessages)
        pendingMessages.forEach { messages.add(LlmMessage(role = "user", content = it)) }
        return messages
    }

    private suspend fun buildSystemContent(
        systemPrompt: String,
        toolDescriptions: String,
        inlineSkillSection: String = "",
        skillCount: Int = 0,
        senderContext: SenderContext? = null,
    ): String {
        val parts = mutableListOf<String>()
        if (systemPrompt.isNotBlank()) parts.add(systemPrompt)
        parts.add(buildCapabilitiesSection(skillCount))
        val now = ZonedDateTime.now()
        val formatted = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
        val status = healthProviderLazy.get().getContextStatus()
        val gatewayLabel = if (status.gatewayConnected) "connected" else "disconnected"
        val uptimeFormatted = formatUptime(status.uptime)
        val sandboxLabel = if (status.sandboxReady) "ready" else "not ready"
        val dockerLabel = if (status.docker) "yes" else "no"
        parts.add(
            "## Environment\n$formatted\n" +
                "Gateway: $gatewayLabel | Uptime: $uptimeFormatted\n" +
                "Jobs: ${status.scheduledJobs} | Sessions: ${status.activeSessions} | Sandbox: $sandboxLabel\n" +
                "Embedding: ${status.embeddingType} | Docker: $dockerLabel",
        )
        if (senderContext != null) {
            val senderSection = buildSenderSection(senderContext)
            if (senderSection != null) {
                parts.add(senderSection)
            }
        }
        if (config.memory.compaction.enabled) {
            parts.add(
                "## Conversation History\n" +
                    "You see a sliding window of the most recent messages, not the full conversation. " +
                    "Older messages are automatically summarized — summaries are included above for " +
                    "continuity. If you need exact details from earlier in the conversation, use " +
                    "`history_search` to find specific past messages by topic.",
            )
        }
        if (config.memory.injectMemoryMap) {
            val memorySummary = workspaceLoader.loadMemorySummary()
            if (memorySummary != null) {
                parts.add(memorySummary)
            }
        }
        if (toolDescriptions.isNotBlank()) parts.add("## Available Tools\n" + toolDescriptions)
        if (inlineSkillSection.isNotBlank()) parts.add("## Available Skills\n" + inlineSkillSection)
        return parts.joinToString("\n\n")
    }

    private fun buildSenderSection(sender: SenderContext): String? {
        val obj =
            buildJsonObject {
                sender.senderName?.let { put("name", it) }
                sender.senderId?.let { put("id", it) }
                sender.chatType?.let { put("chat_type", it) }
                sender.platform?.let { put("platform", it) }
                sender.chatTitle?.let { put("chat_title", it) }
                sender.messageId?.let { put("message_id", it) }
            }
        if (obj.isEmpty()) return null
        return "## Current Sender\n```json\n$obj\n```"
    }

    private fun formatUptime(duration: java.time.Duration): String {
        val days = duration.toDays()
        val hours = duration.toHours() % HOURS_PER_DAY
        val minutes = duration.toMinutes() % MINUTES_PER_HOUR
        return buildString {
            if (days > 0) append("${days}d ")
            if (hours > 0 || days > 0) append("${hours}h ")
            append("${minutes}m")
        }.trim()
    }

    companion object {
        private const val HOURS_PER_DAY = 24
        private const val MINUTES_PER_HOUR = 60
        private const val DEFAULT_VISION_MAX_TOKENS = 1024
    }

    private fun buildCapabilitiesSection(skillCount: Int): String =
        buildString {
            append("## Your Capabilities\n")
            append(
                "You are a personal AI assistant running on the Klaw platform. " +
                    "You have persistent long-term memory that survives across conversations, " +
                    "can search your conversation history, and manage scheduled tasks and reminders. " +
                    "You can execute code in an isolated sandbox, read and write files in your workspace, " +
                    "and manage your own configuration at runtime. " +
                    "You can delegate work to independent subagents for parallel execution.",
            )
            if (config.docs.enabled) {
                append(
                    " You have a documentation library — " +
                        "when asked about yourself, your architecture, or how you work, search it first.",
                )
            }
            if (config.hostExecution.enabled) {
                append(" You can execute commands directly on the host system.")
            }
            if (skillCount > 0) {
                append(" You have extensible skills that provide specialized workflows.")
            }
        }
}
