package io.github.klaw.engine.tools

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolDef
import io.github.klaw.common.llm.ToolResult
import io.github.klaw.engine.context.ToolRegistry
import io.github.klaw.engine.context.stubs.StubToolRegistry
import io.github.klaw.engine.workspace.HeartbeatDeliverContext
import io.github.klaw.engine.workspace.ScheduleDeliverContext
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private val config: EngineConfig,
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
        if (!includeSkillList) result = result.filter { it.name != SKILL_LIST_TOOL_NAME }
        if (!includeSkillLoad) result = result.filter { it.name != SKILL_LOAD_TOOL_NAME }
        if (!includeSendMessage) result = result.filter { it.name != SEND_MESSAGE_TOOL_NAME }
        if (includeHeartbeatDeliver) result = result + HEARTBEAT_DELIVER_DEF
        if (includeScheduleDeliver) result = result + SCHEDULE_DELIVER_DEF
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
            } catch (e: Exception) {
                logger.warn(e) { "execute failed: tool=${call.name}" }
                "Error: ${e.message}"
            }
        return ToolResult(callId = call.id, content = result)
    }

    @Suppress("CyclomaticComplexity", "LongMethod", "CyclomaticComplexMethod")
    private suspend fun dispatch(
        name: String,
        args: JsonObject,
    ): String =
        when (name) {
            "file_read" -> {
                fileTools.read(
                    args.str("path"),
                    args.intOrNull("startLine"),
                    args.intOrNull("maxLines"),
                )
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

            "memory_search" -> {
                memoryTools.search(args.str("query"), args.intOrNull("topK") ?: DEFAULT_MEMORY_TOP_K)
            }

            "memory_save" -> {
                memoryTools.save(args.str("content"), args.strOrNull("source") ?: "manual")
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

            "subagent_spawn" -> {
                subagentTools.spawn(
                    args.str("name"),
                    args.str("message"),
                    args.strOrNull("model"),
                    args.strOrNull("injectInto"),
                )
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
                    args.str("language"),
                    args.str("code"),
                    args.intOrNull("timeout") ?: config.codeExecution.timeout,
                )
            }

            "host_exec" -> {
                val ctx = kotlin.coroutines.coroutineContext[ChatContext]
                hostExecTool.execute(args.str("command"), ctx?.chatId ?: "unknown", ctx?.channel ?: "unknown")
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
                logger.warn { "unknown tool: '$name'" }
                "Error: unknown tool '$name'"
            }
        }

    private fun JsonObject.str(key: String): String =
        this[key]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required parameter: $key")

    private fun JsonObject.strOrNull(key: String): String? = this[key]?.jsonPrimitive?.content

    private fun JsonObject.intOrNull(key: String): Int? = this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.boolOrNull(key: String): Boolean? = this[key]?.jsonPrimitive?.booleanOrNull

    companion object {
        private const val DEFAULT_MEMORY_TOP_K = 10
        private const val DEFAULT_DOCS_TOP_K = 5
        private val DOCS_TOOL_NAMES = setOf("docs_search", "docs_read", "docs_list")
        private const val HOST_EXEC_TOOL_NAME = "host_exec"
        private const val SKILL_LIST_TOOL_NAME = "skill_list"
        private const val SKILL_LOAD_TOOL_NAME = "skill_load"
        private const val SEND_MESSAGE_TOOL_NAME = "send_message"
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
                        "For other dirs use absolute paths (e.g. \$STATE/logs/engine.log).",
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
                    "memory_search",
                    "Search long-term memory",
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
                    "Save information to long-term memory",
                    toolParams(
                        listOf("content"),
                        mapOf(
                            "content" to stringProp("Text to save"),
                            "source" to stringProp("Information source (default: manual)"),
                        ),
                    ),
                ),
                ToolDef(
                    "docs_search",
                    "Search project documentation",
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
                    "Read a document by path",
                    toolParams(
                        listOf("path"),
                        mapOf("path" to stringProp("Document path")),
                    ),
                ),
                ToolDef(
                    "docs_list",
                    "List available documents",
                    toolParams(emptyList(), emptyMap()),
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
                    "Add a scheduled or one-time task. The 'message' field must be an explicit instruction " +
                        "for the subagent, not just content to deliver.",
                    toolParams(
                        listOf("name", "message"),
                        mapOf(
                            "name" to stringProp("Unique task name"),
                            "cron" to stringProp("Cron schedule expression (mutually exclusive with at)"),
                            "at" to stringProp("ISO-8601 datetime for one-time trigger (mutually exclusive with cron)"),
                            "message" to
                                stringProp(
                                    "Explicit instruction for the subagent when the task fires. " +
                                        "Must be an actionable task, not just content. " +
                                        "For reminder delivery: 'Your task: send the user this reminder: <text>'. " +
                                        "For data collection: 'Check <source>, extract <info>, summarize for user'.",
                                ),
                            "model" to stringProp("LLM model (optional)"),
                        ),
                    ),
                ),
                ToolDef(
                    "schedule_remove",
                    "Remove a scheduled task",
                    toolParams(
                        listOf("name"),
                        mapOf("name" to stringProp("Task name to remove")),
                    ),
                ),
                ToolDef(
                    "subagent_spawn",
                    "Spawn a subagent to perform a task",
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
                @Suppress("MaxLineLength")
                ToolDef(
                    "sandbox_exec",
                    "PREFERRED tool for code execution. Runs Python or bash in an isolated Docker container with workspace access ($SANDBOX_WORKSPACE_PATH). Use for: data processing, downloading files (curl/wget), file transformations, computations, parsing, working with workspace files, testing scripts. Project workspace is mounted at $SANDBOX_WORKSPACE_PATH — code can read and write workspace files.",
                    toolParams(
                        listOf("language", "code"),
                        mapOf(
                            "language" to stringProp("Language: python or bash"),
                            "code" to stringProp("Code to execute"),
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
            )
    }
}
