package io.github.klaw.engine.tools

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.common.llm.ToolResult
import io.github.klaw.common.registry.ModelRegistry
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.context.stubs.StubToolRegistry
import io.github.klaw.engine.mcp.McpToolRegistry
import io.github.klaw.engine.message.INLINE_IMAGE_PREFIX
import io.github.klaw.engine.message.INLINE_IMAGE_SEPARATOR
import io.github.klaw.engine.workspace.HeartbeatDeliverContext
import io.github.klaw.engine.workspace.ScheduleDeliverContext
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

@Singleton
@Replaces(StubToolRegistry::class)
@Suppress("TooManyFunctions", "LongParameterList")
class ToolRegistryImpl(
    private val fileTools: FileTools,
    private val skillTools: SkillTools,
    private val memoryTools: MemoryTools,
    private val docsTools: DocsTools,
    private val scheduleTools: ScheduleTools,
    private val subagentTools: SubagentTools,
    private val utilityTools: UtilityTools,
    private val sandboxExecTool: SandboxExecTool,
    private val hostExecTool: HostExecTool,
    private val configTools: ConfigTools,
    private val historyTools: HistoryTools,
    private val engineHealthTools: EngineHealthTools,
    private val subagentStatusTools: SubagentStatusTools,
    private val webFetchTool: WebFetchTool,
    private val webSearchTool: WebSearchTool,
    private val pdfReadTool: PdfReadTool,
    private val mdToPdfTool: MdToPdfTool,
    private val imageAnalyzeTool: ImageAnalyzeTool,
    private val config: EngineConfig,
    private val mcpToolRegistry: McpToolRegistry,
) : ToolRegistry {
    override suspend fun listTools(
        includeSkillList: Boolean,
        includeSkillLoad: Boolean,
        includeHeartbeatDeliver: Boolean,
        includeScheduleDeliver: Boolean,
        includeSendMessage: Boolean,
    ): List<ToolDef> {
        var result = if (config.docs.enabled) toolDefs else toolDefs.filter { it.name !in DOCS_TOOL_NAMES }
        if (!config.hostExecution.enabled) result = result.filter { it.name != HOST_EXEC_TOOL_NAME }
        if (!config.web.fetch.enabled) result = result.filter { it.name != WEB_FETCH_TOOL_NAME }
        if (!config.web.search.enabled) result = result.filter { it.name != WEB_SEARCH_TOOL_NAME }
        if (!config.vision.enabled) result = result.filter { it.name != IMAGE_ANALYZE_TOOL_NAME }
        if (!includeSkillList) result = result.filter { it.name != SKILL_LIST_TOOL_NAME }
        if (!includeSkillLoad) result = result.filter { it.name != SKILL_LOAD_TOOL_NAME }
        if (!includeSendMessage) result = result.filter { it.name != SEND_MESSAGE_TOOL_NAME }
        if (includeHeartbeatDeliver) result = result + HEARTBEAT_DELIVER_DEF
        if (includeScheduleDeliver) result = result + SCHEDULE_DELIVER_DEF
        result = result + mcpToolRegistry.listTools()
        return result
    }

    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(call: ToolCall): ToolResult {
        logger.trace { "tool: name=${call.name}" }
        val result =
            try {
                val args =
                    if (call.arguments.isBlank()) {
                        JsonObject(emptyMap())
                    } else {
                        Json.parseToJsonElement(call.arguments).jsonObject
                    }
                dispatch(call.name, args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(e) { "execute failed: tool=${call.name}" }
                "Error: ${e.message}"
            }
        logger.trace { "Tool completed: name=${call.name} resultLen=${result.length}" }
        return ToolResult(callId = call.id, content = result)
    }

    @Suppress("CyclomaticComplexity", "LongMethod", "CyclomaticComplexMethod")
    private suspend fun dispatch(
        name: String,
        args: JsonObject,
    ): String =
        when (name) {
            "file_read" -> {
                val path = args.str("path")
                val ext = path.substringAfterLast('.', "").lowercase()
                if (ext in IMAGE_EXTENSIONS) {
                    handleImageFileRead(path)
                } else {
                    fileTools.read(
                        path,
                        args.intOrNull("startLine"),
                        args.intOrNull("maxLines"),
                        args.intOrNull("tail"),
                    )
                }
            }

            "file_write" -> {
                fileTools.write(args.str("path"), args.str("content"), args.str("mode"))
            }

            "file_list" -> {
                fileTools.list(args.str("path"), args.boolOrNull("recursive") ?: false)
            }

            "file_patch" -> {
                fileTools.patch(
                    args.str("path"),
                    args.str("old_string"),
                    args.str("new_string"),
                    args.boolOrNull("force_first") ?: false,
                )
            }

            "file_glob" -> {
                fileTools.glob(args.str("pattern"), args.strOrNull("path"))
            }

            "memory_search" -> {
                memoryTools.search(args.str("query"), args.intOrNull("topK") ?: DEFAULT_MEMORY_TOP_K)
            }

            "memory_save" -> {
                memoryTools.save(
                    args.str("content"),
                    args.str("category"),
                    args.strOrNull("source") ?: "manual",
                )
            }

            "memory_rename_category" -> {
                memoryTools.renameCategory(args.str("oldName"), args.str("newName"))
            }

            "memory_merge_categories" -> {
                val sources = args.strList("sourceNames")
                memoryTools.mergeCategories(sources, args.str("targetName"))
            }

            "memory_delete_category" -> {
                memoryTools.deleteCategory(
                    args.str("name"),
                    args.boolOrNull("deleteFacts") ?: true,
                )
            }

            "history_search" -> {
                val ctx = kotlin.coroutines.coroutineContext[ChatContext]
                historyTools.search(
                    args.str("query"),
                    ctx?.chatId ?: "unknown",
                    args.intOrNull("topK") ?: DEFAULT_HISTORY_TOP_K,
                )
            }

            "docs_search" -> {
                docsTools.search(args.str("query"), args.intOrNull("topK") ?: DEFAULT_DOCS_TOP_K)
            }

            "docs_read" -> {
                docsTools.read(args.str("path"))
            }

            "docs_list" -> {
                docsTools.list()
            }

            "skill_list" -> {
                skillTools.list()
            }

            "skill_load" -> {
                skillTools.load(args.str("name"))
            }

            "schedule_list" -> {
                scheduleTools.list()
            }

            "schedule_add" -> {
                val ctx = kotlin.coroutines.coroutineContext[ChatContext]
                scheduleTools.add(
                    args.str("name"),
                    args.strOrNull("cron"),
                    args.strOrNull("at"),
                    args.str("message"),
                    args.strOrNull("model"),
                    ctx?.chatId,
                    ctx?.channel,
                )
            }

            "schedule_remove" -> {
                scheduleTools.remove(args.str("name"))
            }

            "schedule_edit" -> {
                scheduleTools.edit(
                    args.str("name"),
                    args.strOrNull("cron"),
                    args.strOrNull("message"),
                    args.strOrNull("model"),
                )
            }

            "schedule_enable" -> {
                scheduleTools.enable(args.str("name"))
            }

            "schedule_disable" -> {
                scheduleTools.disable(args.str("name"))
            }

            "subagent_spawn" -> {
                subagentTools.spawn(
                    args.str("name"),
                    args.str("message"),
                    args.strOrNull("model"),
                    args.strOrNull("injectInto"),
                )
            }

            "subagent_status" -> {
                val ctx =
                    kotlin.coroutines.coroutineContext[ChatContext]
                        ?: error("subagent_status called outside session context")
                subagentStatusTools.status(args.str("id"), ctx.chatId)
            }

            "subagent_list" -> {
                val ctx =
                    kotlin.coroutines.coroutineContext[ChatContext]
                        ?: error("subagent_list called outside session context")
                subagentStatusTools.list(ctx.chatId)
            }

            "subagent_cancel" -> {
                val ctx =
                    kotlin.coroutines.coroutineContext[ChatContext]
                        ?: error("subagent_cancel called outside session context")
                subagentStatusTools.cancel(args.str("id"), ctx.chatId)
            }

            "send_message" -> {
                utilityTools.sendMessage(
                    args.str("channel"),
                    args.str("chatId"),
                    args.str("text"),
                )
            }

            "sandbox_exec" -> {
                sandboxExecTool.execute(
                    args.str("code"),
                    args.intOrNull("timeout") ?: config.codeExecution.timeout,
                )
            }

            "host_exec" -> {
                val ctx = kotlin.coroutines.coroutineContext[ChatContext]
                hostExecTool.execute(args.str("command"), ctx?.chatId ?: "unknown", ctx?.channel ?: "unknown")
            }

            "config_get" -> {
                configTools.configGet(args.str("target"), args.strOrNull("path"))
            }

            "config_set" -> {
                configTools.configSet(args.str("target"), args.str("path"), args.str("value"))
            }

            "engine_health" -> {
                engineHealthTools.health()
            }

            "web_fetch" -> {
                webFetchTool.fetch(args.str("url"), args.intOrNull("timeout_seconds"))
            }

            "web_search" -> {
                webSearchTool.search(args.str("query"), args.intOrNull("max_results"))
            }

            "pdf_read" -> {
                pdfReadTool.read(args.str("path"), args.intOrNull("start_page"), args.intOrNull("end_page"))
            }

            "md_to_pdf" -> {
                mdToPdfTool.convert(args.str("input_path"), args.str("output_path"), args.strOrNull("title"))
            }

            "image_analyze" -> {
                imageAnalyzeTool.analyze(args.str("path"), args.strOrNull("prompt"))
            }

            "heartbeat_deliver" -> {
                val ctx =
                    kotlin.coroutines.coroutineContext[HeartbeatDeliverContext]
                        ?: error("heartbeat_deliver called outside heartbeat context")
                ctx.sink.deliver(args.str("message"))
                "Message queued for delivery"
            }

            "schedule_deliver" -> {
                val ctx =
                    kotlin.coroutines.coroutineContext[ScheduleDeliverContext]
                        ?: error("schedule_deliver called outside schedule context")
                ctx.sink.deliver(args.str("message"))
                "Message queued for delivery"
            }

            else -> {
                if (mcpToolRegistry.canHandle(name)) {
                    mcpToolRegistry.execute(name, args)
                } else {
                    logger.warn { "unknown tool: '$name'" }
                    "Error: unknown tool '$name'"
                }
            }
        }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required parameter: $key")

    private fun JsonObject.strOrNull(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.boolOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.strList(key: String): List<String> =
        this[key]?.jsonArray?.map { it.jsonPrimitive.content }
            ?: throw IllegalArgumentException("Missing required parameter: $key")

    /**
     * Handles file_read on an image file. If the current model supports images (per ModelRegistry),
     * returns an inline image marker so ToolCallLoopRunner injects the image directly into context.
     * If not vision-capable, falls back to ImageAnalyzeTool for text description.
     */
    private suspend fun handleImageFileRead(path: String): String {
        if (!config.vision.enabled) {
            return "Error: Cannot read image file — vision is not enabled. " +
                "Configure vision in engine config."
        }

        val ctx = kotlin.coroutines.coroutineContext[ChatContext]
        val modelId = ctx?.modelId ?: ""
        val modelIdWithoutProvider = modelId.substringAfter('/')

        return if (ModelRegistry.supportsImage(modelIdWithoutProvider)) {
            val resolvedPath =
                imageAnalyzeTool.resolveAndValidate(path)
                    ?: return imageAnalyzeTool.analyze(path, ImageAnalyzeTool.DEFAULT_PROMPT)
            "$INLINE_IMAGE_PREFIX${resolvedPath.first}$INLINE_IMAGE_SEPARATOR${resolvedPath.second}"
        } else {
            imageAnalyzeTool.analyze(path, ImageAnalyzeTool.DEFAULT_PROMPT)
        }
    }

    companion object {
        private const val DEFAULT_MEMORY_TOP_K = 10
        private const val DEFAULT_HISTORY_TOP_K = 10
        private const val DEFAULT_DOCS_TOP_K = 5
        private val DOCS_TOOL_NAMES = setOf("docs_search", "docs_read", "docs_list")
        private const val HOST_EXEC_TOOL_NAME = "host_exec"
        private const val SKILL_LIST_TOOL_NAME = "skill_list"
        private const val SKILL_LOAD_TOOL_NAME = "skill_load"
        private const val SEND_MESSAGE_TOOL_NAME = "send_message"
        private const val WEB_FETCH_TOOL_NAME = "web_fetch"
        private const val WEB_SEARCH_TOOL_NAME = "web_search"
        private const val IMAGE_ANALYZE_TOOL_NAME = "image_analyze"
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp")
        private val HEARTBEAT_DELIVER_DEF =
            ToolDef(
                "heartbeat_deliver",
                "Deliver a message to the user. This is the only way to communicate results.",
                toolParams(
                    listOf("message"),
                    mapOf("message" to stringProp("Message text to deliver")),
                ),
            )

        private val SCHEDULE_DELIVER_DEF =
            ToolDef(
                "schedule_deliver",
                "Deliver a message to the user. This is the only way to send a result. " +
                    "If you have nothing to deliver, do not call this tool.",
                toolParams(
                    listOf("message"),
                    mapOf("message" to stringProp("Message text to deliver to the user")),
                ),
            )

        private val toolDefs =
            listOf(
                ToolDef(
                    "file_read",
                    "Read a file. Accessible dirs: workspace (\$WORKSPACE), state (\$STATE — has logs/), " +
                        "data (\$DATA), config (\$CONFIG), cache (\$CACHE). " +
                        "Relative paths resolve to workspace only. " +
                        "For other dirs use absolute paths (e.g. \$STATE/logs/engine.log). " +
                        "Use startLine+maxLines for head/range, or tail for last N lines.",
                    toolParams(
                        listOf("path"),
                        mapOf(
                            "path" to
                                stringProp(
                                    "File path — relative (workspace only) or absolute within " +
                                        "workspace/state/data/config/cache dirs",
                                ),
                            "startLine" to intProp("Start line (1-based)"),
                            "maxLines" to intProp("Maximum number of lines"),
                            "tail" to intProp("Read last N lines (mutually exclusive with startLine/maxLines)"),
                        ),
                    ),
                ),
                ToolDef(
                    "file_write",
                    "Write content to a file",
                    toolParams(
                        listOf("path", "content", "mode"),
                        mapOf(
                            "path" to stringProp("File path relative to workspace"),
                            "content" to stringProp("Content to write"),
                            "mode" to stringProp("Write mode: overwrite or append"),
                        ),
                    ),
                ),
                ToolDef(
                    "file_list",
                    "List directory contents. Same accessible dirs as file_read. " +
                        "Relative paths resolve to workspace only; use absolute paths for state/data/config/cache.",
                    toolParams(
                        listOf("path"),
                        mapOf(
                            "path" to
                                stringProp(
                                    "Directory path — relative (workspace only) or absolute within " +
                                        "workspace/state/data/config/cache dirs",
                                ),
                            "recursive" to boolProp("Recursively traverse subdirectories"),
                        ),
                    ),
                ),
                ToolDef(
                    "file_patch",
                    "Replace a text fragment in a file by exact match",
                    toolParams(
                        listOf("path", "old_string", "new_string"),
                        mapOf(
                            "path" to stringProp("File path relative to workspace"),
                            "old_string" to stringProp("Text to replace (exact match)"),
                            "new_string" to stringProp("New text"),
                            "force_first" to boolProp("Replace first occurrence on multiple matches"),
                        ),
                    ),
                ),
                ToolDef(
                    "file_glob",
                    "Search for files by glob pattern. Same accessible dirs as file_read. " +
                        "Pattern syntax: * (any name), ** (any path), *.kt (by extension), " +
                        "src/**/*.kt (recursive in dir). Returns up to 1000 matching file paths.",
                    toolParams(
                        listOf("pattern"),
                        mapOf(
                            "pattern" to stringProp("Glob pattern (e.g. **/*.kt, src/*.json, *.md)"),
                            "path" to
                                stringProp(
                                    "Base directory to search in — relative (workspace only) or absolute. " +
                                        "Defaults to workspace root",
                                ),
                        ),
                    ),
                ),
                ToolDef(
                    "memory_search",
                    "Search long-term memory for previously saved information. " +
                        "Use when the user references something you should remember, " +
                        "or when you need context from past interactions.",
                    toolParams(
                        listOf("query"),
                        mapOf(
                            "query" to stringProp("Search query"),
                            "topK" to intProp("Number of results (default 10)"),
                        ),
                    ),
                ),
                ToolDef(
                    "memory_save",
                    "Save information to long-term memory that persists across conversations. " +
                        "Use when the user asks you to remember something " +
                        "or when you learn important facts worth retaining. " +
                        "Specify a category to organize facts — use an existing category or create a new one.",
                    toolParams(
                        listOf("content", "category"),
                        mapOf(
                            "content" to stringProp("Text to save"),
                            "category" to stringProp("Memory category (e.g. 'User preferences', 'Project notes')"),
                            "source" to stringProp("Information source (default: manual)"),
                        ),
                    ),
                ),
                ToolDef(
                    "memory_rename_category",
                    "Rename a memory category",
                    toolParams(
                        listOf("oldName", "newName"),
                        mapOf(
                            "oldName" to stringProp("Current category name"),
                            "newName" to stringProp("New category name"),
                        ),
                    ),
                ),
                ToolDef(
                    "memory_merge_categories",
                    "Merge memory categories into one",
                    toolParams(
                        listOf("sourceNames", "targetName"),
                        mapOf(
                            "sourceNames" to stringArrayProp("Category names to merge"),
                            "targetName" to stringProp("Target category name"),
                        ),
                    ),
                ),
                ToolDef(
                    "memory_delete_category",
                    "Delete a memory category",
                    toolParams(
                        listOf("name"),
                        mapOf(
                            "name" to stringProp("Category name"),
                            "deleteFacts" to boolProp("Delete facts too (default true)"),
                        ),
                    ),
                ),
                ToolDef(
                    "history_search",
                    "Search past conversation messages semantically. " +
                        "Returns matching messages from this chat's history with timestamps. " +
                        "Use when you need to recall what was discussed earlier " +
                        "but the details are outside your current context window.",
                    toolParams(
                        listOf("query"),
                        mapOf(
                            "query" to stringProp("Search query"),
                            "topK" to intProp("Number of results (default 10)"),
                        ),
                    ),
                ),
                ToolDef(
                    "docs_search",
                    "Search project documentation. " +
                        "Use this when asked about your own capabilities, architecture, configuration, " +
                        "available tools, or how you work — the docs contain this information.",
                    toolParams(
                        listOf("query"),
                        mapOf(
                            "query" to stringProp("Search query"),
                            "topK" to intProp("Number of results (default 5)"),
                        ),
                    ),
                ),
                ToolDef(
                    "docs_read",
                    "Read a document by path. Use after docs_search to read full content of a relevant document.",
                    toolParams(
                        listOf("path"),
                        mapOf("path" to stringProp("Document path")),
                    ),
                ),
                ToolDef(
                    "docs_list",
                    "List all available documents in the documentation library. " +
                        "Use to discover what documentation exists.",
                    toolParams(emptyList(), emptyMap()),
                ),
                ToolDef(
                    "pdf_read",
                    "Read a PDF and extract text",
                    toolParams(
                        listOf("path"),
                        mapOf(
                            "path" to stringProp("PDF file path"),
                            "start_page" to intProp("First page (1-based)"),
                            "end_page" to intProp("Last page (1-based)"),
                        ),
                    ),
                ),
                ToolDef(
                    "md_to_pdf",
                    "Convert Markdown to PDF",
                    toolParams(
                        listOf("input_path", "output_path"),
                        mapOf(
                            "input_path" to stringProp("Markdown file path"),
                            "output_path" to stringProp("Output PDF path"),
                            "title" to stringProp("Document title"),
                        ),
                    ),
                ),
                ToolDef(
                    "skill_list",
                    "List available skills",
                    toolParams(emptyList(), emptyMap()),
                ),
                ToolDef(
                    "skill_load",
                    "Load full skill content",
                    toolParams(
                        listOf("name"),
                        mapOf("name" to stringProp("Skill name")),
                    ),
                ),
                ToolDef(
                    "schedule_list",
                    "List scheduled tasks",
                    toolParams(emptyList(), emptyMap()),
                ),
                ToolDef(
                    "schedule_add",
                    "Add a scheduled or one-time task",
                    toolParams(
                        listOf("name", "message"),
                        mapOf(
                            "name" to stringProp("Unique task name"),
                            "message" to stringProp("Instruction for the subagent"),
                            "cron" to stringProp("Cron expression (mutually exclusive with at)"),
                            "at" to stringProp("ISO-8601 datetime (mutually exclusive with cron)"),
                            "model" to stringProp("LLM model override"),
                        ),
                    ),
                ),
                ToolDef(
                    "schedule_remove",
                    "Remove a scheduled task",
                    toolParams(
                        listOf("name"),
                        mapOf("name" to stringProp("Task name")),
                    ),
                ),
                ToolDef(
                    "schedule_edit",
                    "Edit a scheduled task",
                    toolParams(
                        listOf("name"),
                        mapOf(
                            "name" to stringProp("Task name"),
                            "cron" to stringProp("New cron expression"),
                            "message" to stringProp("New instruction"),
                            "model" to stringProp("New LLM model"),
                        ),
                    ),
                ),
                ToolDef(
                    "schedule_enable",
                    "Resume a paused task",
                    toolParams(
                        listOf("name"),
                        mapOf("name" to stringProp("Task name")),
                    ),
                ),
                ToolDef(
                    "schedule_disable",
                    "Pause a scheduled task",
                    toolParams(
                        listOf("name"),
                        mapOf("name" to stringProp("Task name")),
                    ),
                ),
                ToolDef(
                    "subagent_spawn",
                    "Spawn an independent subagent to perform a task in parallel. " +
                        "The subagent runs with its own context and can use all tools. " +
                        "Use for long-running or independent tasks that don't need your immediate attention. " +
                        "Returns JSON with run ID for tracking via subagent_status/subagent_list.",
                    toolParams(
                        listOf("name", "message"),
                        mapOf(
                            "name" to stringProp("Subagent name"),
                            "message" to stringProp("Task for the subagent"),
                            "model" to stringProp("LLM model (optional)"),
                            "injectInto" to stringProp("chatId to send result to (optional)"),
                        ),
                    ),
                ),
                ToolDef(
                    "subagent_status",
                    "Get the status of a subagent run by ID. " +
                        "Returns JSON with status (RUNNING/COMPLETED/FAILED/CANCELLED), timing, and results.",
                    toolParams(
                        listOf("id"),
                        mapOf("id" to stringProp("Subagent run ID (returned by subagent_spawn)")),
                    ),
                ),
                ToolDef(
                    "subagent_list",
                    "List recent subagent runs spawned from this session. " +
                        "Returns JSON array with status, timing, and results for each run.",
                    toolParams(emptyList(), emptyMap()),
                ),
                ToolDef(
                    "subagent_cancel",
                    "Cancel a running subagent by ID. Only works for subagents spawned from this session.",
                    toolParams(
                        listOf("id"),
                        mapOf("id" to stringProp("Subagent run ID to cancel")),
                    ),
                ),
                @Suppress("MaxLineLength")
                ToolDef(
                    "sandbox_exec",
                    "Execute bash scripts in an isolated Docker container with workspace access ($SANDBOX_WORKSPACE_PATH). Use for: data processing, downloading files (curl/wget), file transformations, computations, parsing, working with workspace files, testing scripts. python3 is available in the container (call from bash). Project workspace is mounted at $SANDBOX_WORKSPACE_PATH — code can read and write workspace files.",
                    toolParams(
                        listOf("code"),
                        mapOf(
                            "code" to stringProp("Bash script to execute"),
                            "timeout" to intProp("Timeout in seconds (default 30)"),
                        ),
                    ),
                ),
                @Suppress("MaxLineLength")
                ToolDef(
                    "host_exec",
                    "Execute a command DIRECTLY on the host system. USE ONLY when sandbox_exec is not possible: hardware monitoring (sensors, lsblk, smartctl), system service management (systemctl), Docker commands, host network diagnostics (ip, ss, ping), actions outside workspace. DO NOT use for: data processing, file downloads, scripts, computations — use sandbox_exec for those.",
                    toolParams(
                        listOf("command"),
                        mapOf(
                            "command" to stringProp("Shell command to execute on host"),
                        ),
                    ),
                ),
                ToolDef(
                    "config_get",
                    "Read engine or gateway configuration",
                    toolParams(
                        listOf("target"),
                        mapOf(
                            "target" to stringProp("Config target: engine or gateway"),
                            "path" to stringProp("Dot-notation path (omit for full config)"),
                        ),
                    ),
                ),
                ToolDef(
                    "config_set",
                    "Update a configuration field",
                    toolParams(
                        listOf("target", "path", "value"),
                        mapOf(
                            "target" to stringProp("Config target: engine or gateway"),
                            "path" to stringProp("Dot-notation path"),
                            "value" to stringProp("New value as string"),
                        ),
                    ),
                ),
                ToolDef(
                    "send_message",
                    "Send a message to a specific channel and chat",
                    toolParams(
                        listOf("channel", "chatId", "text"),
                        mapOf(
                            "channel" to stringProp("Channel (telegram, discord, etc.)"),
                            "chatId" to stringProp("Chat identifier"),
                            "text" to stringProp("Message text"),
                        ),
                    ),
                ),
                ToolDef(
                    "engine_health",
                    "Get engine health status including gateway connection, uptime, database, " +
                        "sandbox, scheduled jobs, active sessions, pending deliveries, and more.",
                    toolParams(emptyList(), emptyMap()),
                ),
                ToolDef(
                    "web_fetch",
                    "Fetch a web page and return its content as readable text/markdown. " +
                        "Strips navigation, ads, and scripts. Returns title, description, and main content. " +
                        "Supports HTML (converted to markdown), plain text, and JSON.",
                    toolParams(
                        listOf("url"),
                        mapOf(
                            "url" to stringProp("URL to fetch (http or https only)"),
                            "timeout_seconds" to intProp("Request timeout in seconds (default 30, max 120)"),
                        ),
                    ),
                ),
                ToolDef(
                    "web_search",
                    "Search the internet and return a list of results with titles, URLs, and snippets. " +
                        "Useful for finding current information, documentation, or answers to questions.",
                    toolParams(
                        listOf("query"),
                        mapOf(
                            "query" to stringProp("Search query"),
                            "max_results" to intProp("Maximum number of results (default 5, max 20)"),
                        ),
                    ),
                ),
                ToolDef(
                    "image_analyze",
                    "Analyze an image file from the workspace. Sends the image to a vision-capable model " +
                        "and returns a detailed text description. Supports JPEG, PNG, GIF, and WebP formats.",
                    toolParams(
                        listOf("path"),
                        mapOf(
                            "path" to
                                stringProp(
                                    "Image file path relative to workspace (e.g. 'screenshots/page.png')",
                                ),
                            "prompt" to
                                stringProp(
                                    "Analysis prompt — what to focus on (default: 'Describe this image in detail')",
                                ),
                        ),
                    ),
                ),
            )
    }
}
