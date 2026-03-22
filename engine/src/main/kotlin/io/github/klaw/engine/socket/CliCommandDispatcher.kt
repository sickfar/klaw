package io.github.klaw.engine.socket

import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.init.InitCliHandler
import io.github.klaw.engine.llm.LlmUsageTracker
import io.github.klaw.engine.llm.ModelUsageSnapshot
import io.github.klaw.engine.maintenance.ReindexService
import io.github.klaw.engine.memory.ConsolidationResult
import io.github.klaw.engine.memory.DailyConsolidationService
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.tools.EngineHealth
import io.github.klaw.engine.tools.EngineHealthProvider
import io.github.klaw.engine.util.VT
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

@Singleton
@Suppress("LongParameterList")
class CliCommandDispatcher(
    private val initCliHandler: InitCliHandler,
    private val sessionManager: SessionManager,
    private val klawScheduler: KlawScheduler,
    private val memoryService: MemoryService,
    private val reindexService: ReindexService,
    private val skillRegistry: SkillRegistry,
    private val consolidationService: DailyConsolidationService,
    private val engineHealthProvider: EngineHealthProvider,
    private val llmUsageTracker: LlmUsageTracker,
) {
    suspend fun dispatch(request: CliRequestMessage): String =
        withContext(Dispatchers.VT) {
            dispatchMemoryCommand(request) ?: dispatchCoreCommand(request)
        }

    private suspend fun dispatchMemoryCommand(request: CliRequestMessage): String? =
        when (request.command) {
            "memory_search" -> {
                handleMemorySearch(request.params)
            }

            "memory_categories_list" -> {
                handleMemoryCategoriesList()
            }

            "memory_categories_rename" -> {
                handleMemoryCategoryOp(request.params, "old_name", "new_name") { a, b ->
                    memoryService.renameCategory(a, b)
                }
            }

            "memory_categories_merge" -> {
                handleMemoryCategoryOp(request.params, "sources", "target") { s, t ->
                    memoryService.mergeCategories(s.split(",").map { it.trim() }.filter { it.isNotEmpty() }, t)
                }
            }

            "memory_categories_delete" -> {
                handleMemoryCategoryOp(request.params, "name", null) { name, _ ->
                    memoryService.deleteCategory(name, deleteFacts = request.params["keep_facts"]?.toBoolean() != true)
                }
            }

            "memory_facts_add" -> {
                handleMemoryCategoryOp(request.params, "category", "content") { cat, content ->
                    memoryService.save(content, cat, source = "cli")
                }
            }

            "memory_consolidate" -> {
                handleMemoryConsolidate(request.params)
            }

            else -> {
                null
            }
        }

    private suspend fun handleMemoryCategoryOp(
        params: Map<String, String>,
        key1: String,
        key2: String?,
        action: suspend (String, String) -> String,
    ): String {
        val v1 = params[key1] ?: return """{"error":"missing $key1"}"""
        val v2 = if (key2 != null) params[key2] ?: return """{"error":"missing $key2"}""" else ""
        return action(v1, v2)
    }

    private suspend fun dispatchCoreCommand(request: CliRequestMessage): String =
        dispatchScheduleCommand(request)
            ?: when (request.command) {
                "klaw_init_status" -> {
                    initCliHandler.handleStatus()
                }

                "klaw_init_generate_identity" -> {
                    initCliHandler.handleGenerateIdentity(request.params)
                }

                "status" -> {
                    handleStatus(request.params)
                }

                "sessions" -> {
                    handleSessions()
                }

                "sessions_list" -> {
                    handleSessionsList(request.params)
                }

                "sessions_cleanup" -> {
                    handleSessionsCleanup(request.params)
                }

                "reindex" -> {
                    handleReindex(request.params)
                }

                "skills_validate" -> {
                    handleSkillsValidate()
                }

                "skills_list" -> {
                    handleSkillsList()
                }

                else -> {
                    val safe = request.command.replace("\\", "\\\\").replace("\"", "\\\"")
                    """{"error":"unknown command: $safe"}"""
                }
            }

    private suspend fun dispatchScheduleCommand(request: CliRequestMessage): String? =
        when (request.command) {
            "schedule_list" -> klawScheduler.list()
            "schedule_add" -> handleScheduleAdd(request.params)
            "schedule_remove" -> handleScheduleRemove(request.params)
            "schedule_edit" -> handleScheduleEdit(request.params)
            "schedule_enable" -> handleScheduleEnable(request.params)
            "schedule_disable" -> handleScheduleDisable(request.params)
            "schedule_run" -> handleScheduleRun(request.params)
            "schedule_runs" -> handleScheduleRuns(request.params)
            "schedule_status" -> klawScheduler.status()
            else -> null
        }

    private suspend fun handleStatus(params: Map<String, String>): String {
        val deep = params["deep"]?.toBoolean() ?: false
        val jsonOutput = params["json"]?.toBoolean() ?: false
        val usage = params["usage"]?.toBoolean() ?: false
        val all = params["all"]?.toBoolean() ?: false
        val showDeep = deep || all
        val showUsage = usage || all

        if (!showDeep && !showUsage && !jsonOutput) {
            return basicStatus()
        }

        return if (jsonOutput) {
            buildStatusJson(showDeep, showUsage)
        } else {
            buildStatusText(showDeep, showUsage)
        }
    }

    private suspend fun basicStatus(): String {
        val sessions = sessionManager.listSessions()
        return """{"status":"ok","engine":"klaw","sessions":${sessions.size}}"""
    }

    private suspend fun buildStatusJson(
        showDeep: Boolean,
        showUsage: Boolean,
    ): String {
        val sessions = sessionManager.listSessions()
        val parts = mutableListOf(""""status":"ok","engine":"klaw","sessions":${sessions.size}""")

        if (showDeep) {
            val health = engineHealthProvider.getHealth()
            val healthJson = statusJson.encodeToString(EngineHealth.serializer(), health)
            parts += """"health":$healthJson"""
        }

        if (showUsage) {
            val usageJson = formatUsageJson(llmUsageTracker.snapshot())
            parts += """"usage":$usageJson"""
        }

        return parts.joinToString(",", "{", "}")
    }

    private fun formatUsageJson(snapshot: Map<String, ModelUsageSnapshot>): String {
        if (snapshot.isEmpty()) return "{}"
        val entries =
            snapshot.entries.joinToString(",") { (model, usage) ->
                val m = escapeJson(model)
                """"$m":{"request_count":${usage.requestCount},""" +
                    """"prompt_tokens":${usage.promptTokens},""" +
                    """"completion_tokens":${usage.completionTokens},""" +
                    """"total_tokens":${usage.totalTokens}}"""
            }
        return "{$entries}"
    }

    private suspend fun buildStatusText(
        showDeep: Boolean,
        showUsage: Boolean,
    ): String {
        val sessions = sessionManager.listSessions()
        val text =
            buildString {
                appendLine("Klaw Engine Status")
                appendLine("==================")
                appendLine("Status: ok")
                appendLine("Sessions: ${sessions.size}")

                if (showDeep) {
                    appendLine()
                    appendDeepStatusText(engineHealthProvider.getHealth())
                }

                if (showUsage) {
                    appendLine()
                    appendUsageText(llmUsageTracker.snapshot())
                }
            }.trimEnd()
        return escapeNewlines(text)
    }

    private fun StringBuilder.appendDeepStatusText(health: EngineHealth) {
        appendLine("── Deep Health ──")
        appendLine("Gateway: ${health.gatewayStatus}")
        appendLine("Uptime: ${health.engineUptime}")
        appendLine("Docker: ${if (health.docker) "yes" else "no"}")
        appendLine("Database: ${if (health.databaseOk) "ok" else "error"}")
        appendLine("Sessions: ${health.activeSessions}")
        appendLine("Scheduled jobs: ${health.scheduledJobs}")
        appendLine("Memory facts: ${health.memoryFacts}")
        appendLine("Pending deliveries: ${health.pendingDeliveries}")
        appendLine("Heartbeat: ${if (health.heartbeatRunning) "running" else "stopped"}")
        appendLine("Embedding: ${health.embeddingService}")
        appendLine("SQLite-vec: ${if (health.sqliteVec) "available" else "unavailable"}")
        appendLine("Docs: ${if (health.docsEnabled) "enabled" else "disabled"}")
        appendLine("Running subagents: ${health.runningSubagents}")
        if (health.mcpServers.isNotEmpty()) {
            appendLine("MCP servers: ${health.mcpServers.joinToString(", ")}")
        }
        appendLine(
            "Sandbox: ${if (health.sandbox.enabled) "enabled" else "disabled"}" +
                " (active=${health.sandbox.containerActive}, executions=${health.sandbox.executions})",
        )
    }

    private fun StringBuilder.appendUsageText(snapshot: Map<String, ModelUsageSnapshot>) {
        appendLine("── LLM Usage ──")
        if (snapshot.isEmpty()) {
            appendLine("No usage data yet.")
        } else {
            snapshot.forEach { (model, usage) ->
                appendLine(
                    "$model: ${usage.requestCount} requests, " +
                        "${usage.promptTokens} prompt tokens, " +
                        "${usage.completionTokens} completion tokens, " +
                        "${usage.totalTokens} total tokens",
                )
            }
        }
    }

    private suspend fun handleSessions(): String {
        val sessions = sessionManager.listSessions()
        return sessions.joinToString(",", "[", "]") { s ->
            """{"chatId":"${escapeJson(s.chatId)}","model":"${escapeJson(s.model)}"}"""
        }
    }

    private suspend fun handleSessionsList(params: Map<String, String>): String {
        val activeMinutes = params["active_minutes"]?.toIntOrNull()
        val verbose = params["verbose"]?.toBoolean() ?: false
        val json = params["json"]?.toBoolean() ?: false

        val sessions =
            if (activeMinutes != null) {
                val threshold = Clock.System.now() - activeMinutes.minutes
                sessionManager.listActiveSessions(threshold)
            } else {
                sessionManager.listSessions()
            }

        return if (json) {
            formatSessionsJson(sessions, verbose)
        } else {
            formatSessionsText(sessions, verbose)
        }
    }

    private suspend fun formatSessionsJson(
        sessions: List<Session>,
        verbose: Boolean,
    ): String {
        val parts =
            sessions.map { s ->
                val base =
                    buildString {
                        append("""{"chatId":"${escapeJson(s.chatId)}"""")
                        append(""","model":"${escapeJson(s.model)}"""")
                        append(""","createdAt":"${s.createdAt}"""")
                        append(""","updatedAt":"${s.updatedAt}"""")
                    }
                if (verbose) {
                    val tokens = sessionManager.getTokenCount(s.chatId)
                    """$base,"totalTokens":$tokens}"""
                } else {
                    "$base}"
                }
            }
        return parts.joinToString(",", "[", "]")
    }

    private suspend fun formatSessionsText(
        sessions: List<Session>,
        verbose: Boolean,
    ): String {
        if (sessions.isEmpty()) return "No active sessions."
        val lines =
            sessions.map { s ->
                if (verbose) {
                    val tokens = sessionManager.getTokenCount(s.chatId)
                    "${s.chatId} (model: ${s.model}, updated: ${s.updatedAt}, tokens: $tokens)"
                } else {
                    "${s.chatId} (model: ${s.model}, updated: ${s.updatedAt})"
                }
            }
        return lines.joinToString("\n")
    }

    private suspend fun handleSessionsCleanup(params: Map<String, String>): String {
        val olderThanMinutes = params["older_than_minutes"]?.toIntOrNull() ?: DEFAULT_CLEANUP_MINUTES
        val threshold = Clock.System.now() - olderThanMinutes.minutes
        val deleted = sessionManager.cleanupSessions(threshold)
        return """{"deleted":$deleted,"message":"Removed $deleted inactive sessions"}"""
    }

    @Suppress("ReturnCount")
    private suspend fun handleScheduleAdd(params: Map<String, String>): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        val cron = params["cron"]
        val at = params["at"]
        val message = params["message"] ?: return """{"error":"missing message"}"""
        val model = params["model"]
        val injectInto = params["inject_into"]
        val channel = params["channel"]
        return klawScheduler.add(name, cron, at, message, model, injectInto, channel)
    }

    private suspend fun handleScheduleRemove(params: Map<String, String>): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        return klawScheduler.remove(name)
    }

    private suspend fun handleScheduleEdit(params: Map<String, String>): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        return klawScheduler.edit(name, params["cron"], params["message"], params["model"])
    }

    private suspend fun handleScheduleEnable(params: Map<String, String>): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        return klawScheduler.enable(name)
    }

    private suspend fun handleScheduleDisable(params: Map<String, String>): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        return klawScheduler.disable(name)
    }

    private suspend fun handleScheduleRun(params: Map<String, String>): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        return klawScheduler.run(name)
    }

    private suspend fun handleScheduleRuns(params: Map<String, String>): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        val rawLimit = params["limit"]?.toIntOrNull() ?: DEFAULT_RUNS_LIMIT
        val limit = rawLimit.coerceIn(1, MAX_RUNS_LIMIT)
        return klawScheduler.runs(name, limit)
    }

    private suspend fun handleMemorySearch(params: Map<String, String>): String {
        val query = params["query"] ?: return """{"error":"missing query"}"""
        val topK = params["top_k"]?.toIntOrNull() ?: DEFAULT_TOP_K
        return memoryService.search(query, topK, trackAccess = true)
    }

    private suspend fun handleMemoryCategoriesList(): String {
        val categories = memoryService.getTopCategories(MAX_CATEGORIES_DISPLAY)
        if (categories.isEmpty()) return "No memory categories found."
        return categories.joinToString("\n") { cat ->
            "${cat.name} (${cat.entryCount} entries, accessed ${cat.accessCount} times)"
        }
    }

    private suspend fun handleReindex(params: Map<String, String>): String {
        val lines = mutableListOf<String>()
        if (params["from_jsonl"] == "true") {
            reindexService.reindexFull(onProgress = { lines += it })
        } else {
            reindexService.reindexVec(onProgress = { lines += it })
        }
        return if (lines.isEmpty()) """{"status":"ok"}""" else lines.joinToString("\n")
    }

    private suspend fun handleSkillsValidate(): String {
        val report = skillRegistry.validate()
        val skillsJson =
            report.skills.joinToString(",", "[", "]") { e ->
                val nameField = if (e.name != null) "\"${escapeJson(e.name)}\"" else "null"
                val errorField = if (e.error != null) ""","error":"${escapeJson(e.error)}"""" else ""
                val dir = escapeJson(e.directory)
                val src = escapeJson(e.source)
                """{"name":$nameField,"directory":"$dir","source":"$src","valid":${e.valid}$errorField}"""
            }
        return """{"skills":$skillsJson,"total":${report.total},"valid":${report.valid},"errors":${report.errors}}"""
    }

    private suspend fun handleSkillsList(): String {
        skillRegistry.discover()
        val skills = skillRegistry.listDetailed()
        val items =
            skills.joinToString(",") {
                val n = escapeJson(it.name)
                val d = escapeJson(it.description)
                val s = escapeJson(it.source)
                """{"name":"$n","description":"$d","source":"$s"}"""
            }
        return """{"skills":[$items],"total":${skills.size}}"""
    }

    private suspend fun handleMemoryConsolidate(params: Map<String, String>): String {
        val dateStr = params["date"]
        val force = params["force"]?.toBoolean() ?: false
        val date =
            if (dateStr != null) {
                try {
                    LocalDate.parse(dateStr)
                } catch (_: IllegalArgumentException) {
                    return """{"error":"invalid date '$dateStr', expected ISO-8601 (e.g. 2026-03-21)"}"""
                }
            } else {
                DailyConsolidationService.yesterday()
            }
        return when (val result = consolidationService.consolidate(date, force)) {
            is ConsolidationResult.Success -> "Consolidation complete for $date: ${result.factsSaved} facts saved"
            is ConsolidationResult.AlreadyConsolidated -> "Already consolidated for $date. Use --force to re-run."
            is ConsolidationResult.TooFewMessages -> "Too few messages for $date, skipping."
            is ConsolidationResult.Disabled -> "Daily consolidation is disabled in config."
        }
    }

    private fun escapeNewlines(value: String): String = value.replace("\r", "\\r").replace("\n", "\\n")

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private companion object {
        private const val DEFAULT_TOP_K = 10
        private const val MAX_CATEGORIES_DISPLAY = 50
        private const val DEFAULT_CLEANUP_MINUTES = 1440
        private const val DEFAULT_RUNS_LIMIT = 20
        private const val MAX_RUNS_LIMIT = 200
        private val statusJson = Json { encodeDefaults = true }
    }
}
