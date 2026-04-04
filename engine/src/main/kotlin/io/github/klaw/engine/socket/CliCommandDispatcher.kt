package io.github.klaw.engine.socket

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.error.KlawError
import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.engine.agent.AgentContext
import io.github.klaw.engine.agent.AgentRegistry
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.init.InitCliHandler
import io.github.klaw.engine.llm.LlmRouter
import io.github.klaw.engine.llm.LlmUsageTracker
import io.github.klaw.engine.llm.ModelUsageSnapshot
import io.github.klaw.engine.maintenance.ReindexService
import io.github.klaw.engine.memory.ConsolidationResult
import io.github.klaw.engine.memory.DailyConsolidationService
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.tools.DoctorDeepProbe
import io.github.klaw.engine.tools.EngineHealth
import io.github.klaw.engine.tools.EngineHealthProvider
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.time.Clock
import kotlin.time.Duration.Companion.minutes

private val logger = KotlinLogging.logger {}

@Singleton
@Suppress("LongParameterList")
class CliCommandDispatcher(
    private val initCliHandler: InitCliHandler,
    private val reindexService: ReindexService,
    private val consolidationService: DailyConsolidationService,
    private val engineHealthProvider: EngineHealthProvider,
    private val llmUsageTracker: LlmUsageTracker,
    private val llmRouter: LlmRouter,
    private val config: EngineConfig,
    private val doctorDeepProbe: DoctorDeepProbe,
    private val commandsCliHandler: CommandsCliHandler,
    private val contextDiagnoseHandler: ContextDiagnoseHandler,
    private val agentRegistry: AgentRegistry,
) {
    suspend fun dispatch(request: CliRequestMessage): String {
        logger.debug { "CLI command: ${request.command} agentId=${request.agentId}" }
        when (request.command) {
            "commands_list" -> return withContext(Dispatchers.VT) { commandsCliHandler.handleCommandsList() }
            "models_list" -> return withContext(Dispatchers.VT) { handleModelsList() }
            "doctor_deep" -> return withContext(Dispatchers.VT) { doctorDeepProbe.probe() }
        }
        val ctx = agentRegistry.getOrNull(request.agentId)
        if (ctx == null) {
            logger.warn { "Unknown agentId=${request.agentId} for CLI command ${request.command}" }
            val safe = request.agentId.replace("\\", "\\\\").replace("\"", "\\\"")
            return """{"error":"unknown agent: $safe"}"""
        }
        val effectiveSessionManager = ctx.sessionManager!!
        val effectiveMemoryService = ctx.memoryService!!
        val effectiveScheduler = ctx.scheduler!!
        val effectiveSkillRegistry = ctx.skillRegistry!!
        val result =
            withContext(Dispatchers.VT) {
                dispatchMemoryCommand(request, effectiveMemoryService)
                    ?: dispatchCoreCommand(
                        request,
                        ctx,
                        effectiveSessionManager,
                        effectiveScheduler,
                        effectiveSkillRegistry,
                    )
            }
        logger.trace { "CLI response: command=${request.command} responseLen=${result.length}" }
        return result
    }

    private suspend fun dispatchMemoryCommand(
        request: CliRequestMessage,
        effectiveMemory: MemoryService,
    ): String? =
        when (request.command) {
            "memory_search" -> {
                handleMemorySearch(request.params, effectiveMemory)
            }

            "memory_categories_list" -> {
                handleMemoryCategoriesList(request.params, effectiveMemory)
            }

            "memory_categories_rename" -> {
                handleMemoryCategoryOp(request.params, "old_name", "new_name") { a, b ->
                    effectiveMemory.renameCategory(a, b)
                }
            }

            "memory_categories_merge" -> {
                handleMemoryCategoryOp(request.params, "sources", "target") { s, t ->
                    effectiveMemory.mergeCategories(s.split(",").map { it.trim() }.filter { it.isNotEmpty() }, t)
                }
            }

            "memory_categories_delete" -> {
                handleMemoryCategoryOp(request.params, "name", null) { name, _ ->
                    effectiveMemory.deleteCategory(
                        name,
                        deleteFacts = request.params["keep_facts"]?.toBoolean() != true,
                    )
                }
            }

            "memory_facts_add" -> {
                handleMemoryCategoryOp(request.params, "category", "content") { cat, content ->
                    effectiveMemory.save(content, cat, source = "cli")
                }
            }

            "memory_facts_list" -> {
                val category =
                    request.params["category"]
                        ?: return """{"error":"missing category"}"""
                effectiveMemory.listFactsByCategory(category)
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

    private suspend fun dispatchCoreCommand(
        request: CliRequestMessage,
        ctx: AgentContext,
        effectiveSession: SessionManager,
        effectiveScheduler: KlawScheduler,
        effectiveSkills: SkillRegistry,
    ): String =
        dispatchScheduleCommand(request, effectiveScheduler)
            ?: dispatchSessionCommand(request, effectiveSession)
            ?: when (request.command) {
                "klaw_init_status" -> {
                    initCliHandler.handleStatus()
                }

                "klaw_init_generate_identity" -> {
                    initCliHandler.handleGenerateIdentity(request.params)
                }

                "status" -> {
                    handleStatus(request.params, effectiveSession)
                }

                "reindex" -> {
                    handleReindex(request.params)
                }

                "skills_validate" -> {
                    handleSkillsValidate(effectiveSkills)
                }

                "skills_list" -> {
                    handleSkillsList(effectiveSkills)
                }

                "context_diagnose" -> {
                    contextDiagnoseHandler.handle(
                        request.params,
                        effectiveSession,
                        ctx.contextBuilder!!,
                    )
                }

                else -> {
                    val safe = request.command.replace("\\", "\\\\").replace("\"", "\\\"")
                    """{"error":"unknown command: $safe"}"""
                }
            }

    private suspend fun dispatchSessionCommand(
        request: CliRequestMessage,
        effectiveSession: SessionManager,
    ): String? =
        when (request.command) {
            "sessions" -> handleSessions(effectiveSession)
            "sessions_list" -> handleSessionsList(request.params, effectiveSession)
            "sessions_cleanup" -> handleSessionsCleanup(request.params, effectiveSession)
            "session_messages" -> handleSessionMessages(request.params, effectiveSession)
            else -> null
        }

    private suspend fun dispatchScheduleCommand(
        request: CliRequestMessage,
        effectiveScheduler: KlawScheduler,
    ): String? =
        when (request.command) {
            "schedule_list" -> {
                val json = request.params["json"]?.toBoolean() ?: false
                if (json) effectiveScheduler.listJson() else effectiveScheduler.list()
            }

            "schedule_add" -> {
                handleScheduleAdd(request.params, effectiveScheduler)
            }

            "schedule_remove" -> {
                handleScheduleRemove(request.params, effectiveScheduler)
            }

            "schedule_edit" -> {
                handleScheduleEdit(request.params, effectiveScheduler)
            }

            "schedule_enable" -> {
                handleScheduleEnable(request.params, effectiveScheduler)
            }

            "schedule_disable" -> {
                handleScheduleDisable(request.params, effectiveScheduler)
            }

            "schedule_run" -> {
                handleScheduleRun(request.params, effectiveScheduler)
            }

            "schedule_runs" -> {
                handleScheduleRuns(request.params, effectiveScheduler)
            }

            "schedule_status" -> {
                effectiveScheduler.status()
            }

            "schedule_import" -> {
                handleScheduleImport(request.params, effectiveScheduler)
            }

            else -> {
                null
            }
        }

    private suspend fun handleStatus(
        params: Map<String, String>,
        effectiveSession: SessionManager,
    ): String {
        val deep = params["deep"]?.toBoolean() ?: false
        val jsonOutput = params["json"]?.toBoolean() ?: false
        val usage = params["usage"]?.toBoolean() ?: false
        val all = params["all"]?.toBoolean() ?: false
        val showDeep = deep || all
        val showUsage = usage || all

        if (!showDeep && !showUsage && !jsonOutput) {
            return basicStatus(effectiveSession)
        }

        return if (jsonOutput) {
            buildStatusJson(showDeep, showUsage, effectiveSession)
        } else {
            buildStatusText(showDeep, showUsage, effectiveSession)
        }
    }

    private suspend fun basicStatus(effectiveSession: SessionManager): String {
        val sessions = effectiveSession.listSessions()
        return """{"status":"ok","engine":"klaw","sessions":${sessions.size}}"""
    }

    private suspend fun buildStatusJson(
        showDeep: Boolean,
        showUsage: Boolean,
        effectiveSession: SessionManager,
    ): String {
        val sessions = effectiveSession.listSessions()
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
        effectiveSession: SessionManager,
    ): String {
        val sessions = effectiveSession.listSessions()
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

    private suspend fun handleSessions(effectiveSession: SessionManager): String {
        val sessions = effectiveSession.listSessions()
        return sessions.joinToString(",", "[", "]") { s ->
            """{"chatId":"${escapeJson(s.chatId)}","model":"${escapeJson(s.model)}"}"""
        }
    }

    private suspend fun handleSessionsList(
        params: Map<String, String>,
        effectiveSession: SessionManager,
    ): String {
        val activeMinutes = params["active_minutes"]?.toIntOrNull()
        val verbose = params["verbose"]?.toBoolean() ?: false
        val json = params["json"]?.toBoolean() ?: false

        val sessions =
            if (activeMinutes != null) {
                val threshold = Clock.System.now() - activeMinutes.minutes
                effectiveSession.listActiveSessions(threshold)
            } else {
                effectiveSession.listSessions()
            }

        return if (json) {
            formatSessionsJson(sessions, verbose, effectiveSession)
        } else {
            formatSessionsText(sessions, verbose, effectiveSession)
        }
    }

    private suspend fun formatSessionsJson(
        sessions: List<Session>,
        verbose: Boolean,
        effectiveSession: SessionManager,
    ): String {
        val parts =
            sessions.map { s ->
                val messageCount = effectiveSession.getMessageCount(s.chatId)
                val base =
                    buildString {
                        append("""{"chatId":"${escapeJson(s.chatId)}"""")
                        append(""","model":"${escapeJson(s.model)}"""")
                        append(""","messageCount":$messageCount""")
                        append(""","createdAt":"${s.createdAt}"""")
                        append(""","updatedAt":"${s.updatedAt}"""")
                    }
                if (verbose) {
                    val tokens = effectiveSession.getTokenCount(s.chatId)
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
        effectiveSession: SessionManager,
    ): String {
        if (sessions.isEmpty()) return "No active sessions."
        val lines =
            sessions.map { s ->
                if (verbose) {
                    val tokens = effectiveSession.getTokenCount(s.chatId)
                    "${s.chatId} (model: ${s.model}, updated: ${s.updatedAt}, tokens: $tokens)"
                } else {
                    "${s.chatId} (model: ${s.model}, updated: ${s.updatedAt})"
                }
            }
        return lines.joinToString("\n")
    }

    private suspend fun handleSessionsCleanup(
        params: Map<String, String>,
        effectiveSession: SessionManager,
    ): String {
        val olderThanMinutes = params["older_than_minutes"]?.toIntOrNull() ?: DEFAULT_CLEANUP_MINUTES
        val threshold = Clock.System.now() - olderThanMinutes.minutes
        val deleted = effectiveSession.cleanupSessions(threshold)
        return """{"deleted":$deleted,"message":"Removed $deleted inactive sessions"}"""
    }

    @Suppress("ReturnCount")
    private suspend fun handleScheduleAdd(
        params: Map<String, String>,
        sched: KlawScheduler,
    ): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        val cron = params["cron"]
        val at = params["at"]
        val message = params["message"] ?: return """{"error":"missing message"}"""
        val model = params["model"]
        val injectInto = params["inject_into"]
        val channel = params["channel"]
        return sched.add(name, cron, at, message, model, injectInto, channel)
    }

    private suspend fun handleScheduleRemove(
        params: Map<String, String>,
        sched: KlawScheduler,
    ): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        return sched.remove(name)
    }

    private suspend fun handleScheduleEdit(
        params: Map<String, String>,
        sched: KlawScheduler,
    ): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        return sched.edit(name, params["cron"], params["message"], params["model"])
    }

    private suspend fun handleScheduleEnable(
        params: Map<String, String>,
        sched: KlawScheduler,
    ): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        return sched.enable(name)
    }

    private suspend fun handleScheduleDisable(
        params: Map<String, String>,
        sched: KlawScheduler,
    ): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        return sched.disable(name)
    }

    private suspend fun handleScheduleRun(
        params: Map<String, String>,
        sched: KlawScheduler,
    ): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        return sched.run(name)
    }

    private suspend fun handleScheduleRuns(
        params: Map<String, String>,
        sched: KlawScheduler,
    ): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        val rawLimit = params["limit"]?.toIntOrNull() ?: DEFAULT_RUNS_LIMIT
        val limit = rawLimit.coerceIn(1, MAX_RUNS_LIMIT)
        return sched.runs(name, limit)
    }

    private suspend fun handleScheduleImport(
        params: Map<String, String>,
        sched: KlawScheduler,
    ): String {
        val content = params["content"] ?: return """{"error":"missing content"}"""
        val includeDisabled = params["all"]?.toBoolean() ?: false
        val jobs =
            parseImportJobs(content, includeDisabled)
                ?: return """{"error":"parse failed: invalid format"}"""
        if (jobs.isEmpty()) return """{"imported":0,"failed":0,"message":"no jobs to import"}"""

        var imported = 0
        var failed = 0
        val errors = mutableListOf<String>()
        for (job in jobs) {
            val result = importSingleJob(job, sched)
            when {
                result == null -> {
                    failed++
                    errors += "${job.name}: conversion error"
                }

                result.contains("error", ignoreCase = true) -> {
                    failed++
                    errors += "${job.name}: $result"
                }

                else -> {
                    imported++
                }
            }
        }
        val errorsJson = if (errors.isEmpty()) "[]" else errors.joinToString(",", "[", "]") { "\"$it\"" }
        return """{"imported":$imported,"failed":$failed,"errors":$errorsJson}"""
    }

    private fun parseImportJobs(
        content: String,
        includeDisabled: Boolean,
    ): List<io.github.klaw.common.migration.OpenClawJob>? =
        try {
            io.github.klaw.common.migration.OpenClawCronConverter
                .parseJobs(content, includeDisabled)
        } catch (e: kotlinx.serialization.SerializationException) {
            logger.warn { "schedule import parse error: ${e::class.simpleName}" }
            null
        } catch (e: IllegalArgumentException) {
            logger.warn { "schedule import parse error: ${e::class.simpleName}" }
            null
        }

    private suspend fun importSingleJob(
        job: io.github.klaw.common.migration.OpenClawJob,
        sched: KlawScheduler,
    ): String? {
        val p =
            try {
                io.github.klaw.common.migration.OpenClawCronConverter
                    .toKlawScheduleParams(job)
            } catch (e: IllegalArgumentException) {
                logger.warn { "schedule import conversion error for ${job.name}: ${e::class.simpleName}" }
                return null
            } catch (e: IllegalStateException) {
                logger.warn { "schedule import conversion error for ${job.name}: ${e::class.simpleName}" }
                return null
            }
        val normalizedModel = p["model"]?.let { resolveModelId(it) }
        return sched.add(
            name = p["name"]!!,
            cron = p["cron"],
            at = p["at"],
            message = p["message"]!!,
            model = normalizedModel,
            injectInto = p["inject_into"],
            channel = p["channel"],
        )
    }

    private fun resolveModelId(rawModel: String): String =
        try {
            val (_, modelRef) = llmRouter.resolve(rawModel)
            modelRef.fullId
        } catch (_: KlawError) {
            logger.warn { "schedule import: could not resolve model, keeping as-is" }
            rawModel
        }

    private suspend fun handleMemorySearch(
        params: Map<String, String>,
        effectiveMemory: MemoryService,
    ): String {
        val query = params["query"] ?: return """{"error":"missing query"}"""
        val topK = params["top_k"]?.toIntOrNull() ?: DEFAULT_TOP_K
        return effectiveMemory.search(query, topK, trackAccess = true)
    }

    private suspend fun handleMemoryCategoriesList(
        params: Map<String, String>,
        effectiveMemory: MemoryService,
    ): String {
        val categories = effectiveMemory.getTopCategories(MAX_CATEGORIES_DISPLAY)
        val jsonOutput = params["json"]?.toBoolean() ?: false
        if (jsonOutput) {
            val total = effectiveMemory.getTotalCategoryCount()
            val items =
                categories.joinToString(",") { cat ->
                    val name = escapeJson(cat.name)
                    """{"id":${cat.id},"name":"$name",""" +
                        """"entryCount":${cat.entryCount},"accessCount":${cat.accessCount}}"""
                }
            return """{"categories":[$items],"total":$total}"""
        }
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

    private suspend fun handleSkillsValidate(effectiveSkills: SkillRegistry): String {
        val report = effectiveSkills.validate()
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

    private suspend fun handleSkillsList(effectiveSkills: SkillRegistry): String {
        effectiveSkills.discover()
        val skills = effectiveSkills.listDetailed()
        val items =
            skills.joinToString(",") {
                val n = escapeJson(it.name)
                val d = escapeJson(it.description)
                val s = escapeJson(it.source)
                """{"name":"$n","description":"$d","source":"$s"}"""
            }
        return """{"skills":[$items],"total":${skills.size}}"""
    }

    private suspend fun handleSessionMessages(
        params: Map<String, String>,
        effectiveSession: SessionManager,
    ): String {
        val chatId = params["chat_id"] ?: return """{"error":"missing chat_id"}"""
        val messages =
            effectiveSession
                .getMessages(chatId)
                .filter { it.role == "user" || (it.role == "assistant" && it.type == "text") }
        return messages.joinToString(",", "[", "]") { msg ->
            """{"role":"${escapeJson(
                msg.role,
            )}","content":"${escapeJson(msg.content)}","timestamp":"${msg.created_at}"}"""
        }
    }

    private fun handleModelsList(): String {
        val items = config.models.keys.joinToString(",") { "\"${escapeJson(it)}\"" }
        return """{"models":[$items]}"""
    }

    private suspend fun handleMemoryConsolidate(params: Map<String, String>): String {
        val dateStr = params["date"]
        val force = params["force"]?.toBoolean() ?: false
        val date =
            if (dateStr != null) {
                try {
                    LocalDate.parse(dateStr)
                } catch (_: IllegalArgumentException) {
                    return """{"error":"invalid date '${escapeJson(dateStr)}', expected ISO-8601 (e.g. 2026-03-21)"}"""
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
