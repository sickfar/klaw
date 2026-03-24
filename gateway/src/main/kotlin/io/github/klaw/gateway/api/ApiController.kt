package io.github.klaw.gateway.api

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.schema.GeneratedSchemas
import io.github.klaw.common.paths.KlawPathsSnapshot
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.config.WebuiEnabledCondition
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Requires
import io.micronaut.http.HttpResponse
import io.micronaut.http.HttpStatus
import io.micronaut.http.MediaType
import io.micronaut.http.annotation.Body
import io.micronaut.http.annotation.Controller
import io.micronaut.http.annotation.Delete
import io.micronaut.http.annotation.Get
import io.micronaut.http.annotation.PathVariable
import io.micronaut.http.annotation.Post
import io.micronaut.http.annotation.Put
import io.micronaut.http.annotation.QueryValue
import java.io.File

private val logger = KotlinLogging.logger {}

@Controller("/api/v1")
@Requires(condition = WebuiEnabledCondition::class)
class ApiController(
    private val engineProxy: EngineApiProxy,
    private val config: GatewayConfig,
    private val paths: KlawPathsSnapshot,
    private val channels: List<Channel>,
) {
    // ── Status ──

    @Get("/status")
    suspend fun status(
        @QueryValue deep: String?,
        @QueryValue usage: String?,
    ): HttpResponse<String> {
        val params = mutableMapOf("json" to "true")
        deep?.let { params["deep"] = it }
        usage?.let { params["usage"] = it }
        return respondJson(engineProxy.send("status", params))
    }

    // ── Sessions ──

    @Get("/sessions")
    suspend fun sessions(
        @QueryValue("active_minutes") activeMinutes: String?,
        @QueryValue verbose: String?,
    ): HttpResponse<String> {
        val params = mutableMapOf("json" to "true")
        activeMinutes?.let { params["active_minutes"] = it }
        verbose?.let { params["verbose"] = it }
        return respondJson(engineProxy.send("sessions_list", params))
    }

    @Get("/sessions/{chatId}/messages")
    suspend fun sessionMessages(
        @PathVariable chatId: String,
    ): HttpResponse<String> = respondJson(engineProxy.send("session_messages", mapOf("chat_id" to chatId)))

    @Delete("/sessions/cleanup")
    suspend fun sessionsCleanup(
        @QueryValue("older_than_minutes") olderThanMinutes: String?,
    ): HttpResponse<String> {
        val params = mutableMapOf<String, String>()
        olderThanMinutes?.let { params["older_than_minutes"] = it }
        return respondJson(engineProxy.send("sessions_cleanup", params))
    }

    // ── Models ──

    @Get("/models")
    suspend fun models(): HttpResponse<String> = respondJson(engineProxy.send("models_list"))

    // ── Memory ──

    @Get("/memory/categories")
    suspend fun memoryCategories(): HttpResponse<String> =
        respondJson(engineProxy.send("memory_categories_list", mapOf("json" to "true")))

    @Get("/memory/search")
    suspend fun memorySearch(
        @QueryValue query: String?,
        @QueryValue("top_k") topK: String?,
    ): HttpResponse<String> {
        if (query.isNullOrBlank()) return respondError(HttpStatus.BAD_REQUEST, "missing query parameter")
        val params = mutableMapOf("query" to query)
        topK?.let { params["top_k"] = it }
        return respondJson(engineProxy.send("memory_search", params))
    }

    @Get("/memory/facts")
    suspend fun memoryFactsList(
        @QueryValue category: String?,
    ): HttpResponse<String> {
        if (category.isNullOrBlank()) return respondError(HttpStatus.BAD_REQUEST, "missing category parameter")
        return respondJson(engineProxy.send("memory_facts_list", mapOf("category" to category)))
    }

    @Post("/memory/facts")
    suspend fun memoryFacts(
        @Body body: String,
    ): HttpResponse<String> {
        val parsed = parseJsonBody(body)
        val category = parsed["category"]
        val content = parsed["content"]
        if (category.isNullOrBlank() || content.isNullOrBlank()) {
            return respondError(HttpStatus.BAD_REQUEST, "missing category or content")
        }
        return respondJson(
            engineProxy.send("memory_facts_add", mapOf("category" to category, "content" to content)),
        )
    }

    @Post("/memory/consolidate")
    suspend fun memoryConsolidate(
        @Body body: String,
    ): HttpResponse<String> {
        val parsed = parseJsonBody(body)
        val params = mutableMapOf<String, String>()
        parsed["date"]?.let { params["date"] = it }
        parsed["force"]?.let { params["force"] = it }
        return respondJson(engineProxy.send("memory_consolidate", params))
    }

    @Delete("/memory/categories/{name}")
    suspend fun memoryCategoryDelete(
        @PathVariable name: String,
    ): HttpResponse<String> = respondJson(engineProxy.send("memory_categories_delete", mapOf("name" to name)))

    @Put("/memory/categories/{name}/rename")
    suspend fun memoryCategoryRename(
        @PathVariable name: String,
        @Body body: String,
    ): HttpResponse<String> {
        val newName = parseJsonBody(body)["new_name"]
        if (newName.isNullOrBlank()) return respondError(HttpStatus.BAD_REQUEST, "missing new_name")
        return respondJson(
            engineProxy.send("memory_categories_rename", mapOf("old_name" to name, "new_name" to newName)),
        )
    }

    @Post("/memory/categories/merge")
    suspend fun memoryCategoriesMerge(
        @Body body: String,
    ): HttpResponse<String> {
        val parsed = parseJsonBody(body)
        val sources = parsed["sources"]
        val target = parsed["target"]
        if (sources.isNullOrBlank() || target.isNullOrBlank()) {
            return respondError(HttpStatus.BAD_REQUEST, "missing sources or target")
        }
        return respondJson(
            engineProxy.send("memory_categories_merge", mapOf("sources" to sources, "target" to target)),
        )
    }

    // ── Schedule ──

    @Get("/schedule/jobs")
    suspend fun scheduleJobs(): HttpResponse<String> =
        respondJson(engineProxy.send("schedule_list", mapOf("json" to "true")))

    @Post("/schedule/jobs")
    suspend fun scheduleAddJob(
        @Body body: String,
    ): HttpResponse<String> = respondJson(engineProxy.send("schedule_add", parseJsonBody(body)))

    @Put("/schedule/jobs/{name}")
    suspend fun scheduleEditJob(
        @PathVariable name: String,
        @Body body: String,
    ): HttpResponse<String> {
        val parsed = parseJsonBody(body).toMutableMap()
        parsed["name"] = name
        return respondJson(engineProxy.send("schedule_edit", parsed))
    }

    @Delete("/schedule/jobs/{name}")
    suspend fun scheduleDeleteJob(
        @PathVariable name: String,
    ): HttpResponse<String> = respondJson(engineProxy.send("schedule_remove", mapOf("name" to name)))

    @Post("/schedule/jobs/{name}/enable")
    suspend fun scheduleEnableJob(
        @PathVariable name: String,
    ): HttpResponse<String> = respondJson(engineProxy.send("schedule_enable", mapOf("name" to name)))

    @Post("/schedule/jobs/{name}/disable")
    suspend fun scheduleDisableJob(
        @PathVariable name: String,
    ): HttpResponse<String> = respondJson(engineProxy.send("schedule_disable", mapOf("name" to name)))

    @Post("/schedule/jobs/{name}/run")
    suspend fun scheduleRunJob(
        @PathVariable name: String,
    ): HttpResponse<String> = respondJson(engineProxy.send("schedule_run", mapOf("name" to name)))

    @Get("/schedule/jobs/{name}/runs")
    suspend fun scheduleJobRuns(
        @PathVariable name: String,
        @QueryValue limit: String?,
    ): HttpResponse<String> {
        val params = mutableMapOf("name" to name)
        limit?.let { params["limit"] = it }
        return respondJson(engineProxy.send("schedule_runs", params))
    }

    @Get("/schedule/status")
    suspend fun scheduleStatus(): HttpResponse<String> = respondJson(engineProxy.send("schedule_status"))

    // ── Skills ──

    @Get("/skills")
    suspend fun skills(): HttpResponse<String> = respondJson(engineProxy.send("skills_list"))

    @Get("/skills/validate")
    suspend fun skillsValidate(): HttpResponse<String> = respondJson(engineProxy.send("skills_validate"))

    // ── Config ──

    @Get("/config/engine")
    fun configEngine(): HttpResponse<String> = handleConfigGet("engine.json")

    @Get("/config/gateway")
    fun configGateway(): HttpResponse<String> = handleConfigGet("gateway.json")

    @Put("/config/engine")
    fun configEnginePut(
        @Body body: String,
    ): HttpResponse<String> = handleConfigPut("engine.json", body)

    @Put("/config/gateway")
    fun configGatewayPut(
        @Body body: String,
    ): HttpResponse<String> = handleConfigPut("gateway.json", body)

    @Get("/config/schema/engine")
    fun configSchemaEngine(): HttpResponse<String> =
        HttpResponse.ok(GeneratedSchemas.ENGINE).contentType(MediaType.APPLICATION_JSON_TYPE)

    @Get("/config/schema/gateway")
    fun configSchemaGateway(): HttpResponse<String> =
        HttpResponse.ok(GeneratedSchemas.GATEWAY).contentType(MediaType.APPLICATION_JSON_TYPE)

    // ── Gateway ──

    @Get("/gateway/channels")
    fun gatewayChannels(): HttpResponse<String> {
        val list =
            channels.joinToString(",") { ch ->
                """{"name":"${ch.name}","alive":${ch.isAlive()}}"""
            }
        return HttpResponse.ok("""{"channels":[$list]}""").contentType(MediaType.APPLICATION_JSON_TYPE)
    }

    @Get("/gateway/health")
    fun gatewayHealth(): HttpResponse<String> {
        val count = channels.count { it.isAlive() }
        return HttpResponse
            .ok("""{"status":"ok","channels":$count}""")
            .contentType(MediaType.APPLICATION_JSON_TYPE)
    }

    // ── Maintenance ──

    @Post("/maintenance/reindex")
    suspend fun maintenanceReindex(
        @Body body: String,
    ): HttpResponse<String> = respondJson(engineProxy.send("reindex", parseJsonBody(body)))

    // ── Helpers ──

    private fun handleConfigGet(filename: String): HttpResponse<String> {
        val configFile = File(paths.config, filename)
        if (!configFile.exists()) {
            return respondError(HttpStatus.NOT_FOUND, "$filename not found")
        }
        val sanitized = ConfigSanitizer.sanitizeJsonString(configFile.readText())
        return HttpResponse.ok(sanitized).contentType(MediaType.APPLICATION_JSON_TYPE)
    }

    private fun handleConfigPut(
        filename: String,
        body: String,
    ): HttpResponse<String> {
        try {
            kotlinx.serialization.json.Json
                .parseToJsonElement(body)
        } catch (_: Exception) {
            return respondError(HttpStatus.BAD_REQUEST, "invalid JSON")
        }
        File(paths.config, filename).writeText(body)
        logger.info { "Config $filename updated via API" }
        return HttpResponse
            .ok("""{"status":"ok","message":"Config saved. Restart to apply."}""")
            .contentType(MediaType.APPLICATION_JSON_TYPE)
    }

    companion object {
        fun respondJson(result: String): HttpResponse<String> {
            val status = if (result.startsWith("""{"error":""")) HttpStatus.BAD_REQUEST else HttpStatus.OK
            return HttpResponse
                .status<String>(status)
                .body(result)
                .contentType(MediaType.APPLICATION_JSON_TYPE)
        }

        fun respondError(
            status: HttpStatus,
            message: String,
        ): HttpResponse<String> =
            HttpResponse
                .status<String>(status)
                .body("""{"error":"${status.code}","message":"$message","status":${status.code}}""")
                .contentType(MediaType.APPLICATION_JSON_TYPE)

        fun parseJsonBody(body: String): Map<String, String> =
            try {
                val element =
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(body)
                val obj = element as? kotlinx.serialization.json.JsonObject ?: return emptyMap()
                obj.mapValues { it.value.toString().trim('"') }
            } catch (_: Exception) {
                emptyMap()
            }
    }
}
