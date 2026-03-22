package io.github.klaw.gateway.api

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.schema.GeneratedSchemas
import io.github.klaw.common.paths.KlawPathsSnapshot
import io.github.klaw.gateway.channel.Channel
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import jakarta.inject.Singleton
import java.io.File

private val logger = KotlinLogging.logger {}

@Singleton
class ApiRoutes(
    private val engineProxy: EngineApiProxy,
    private val config: GatewayConfig,
    private val paths: KlawPathsSnapshot,
    private val channels: List<Channel>,
) {
    fun install(routing: Route) {
        routing.route("/api/v1") {
            val token = resolveApiToken()
            if (token != null) {
                install(bearerAuthPlugin(token))
            }
            installStatusRoutes(this)
            installSessionRoutes(this)
            installMemoryRoutes(this)
            installScheduleRoutes(this)
            installSkillRoutes(this)
            installConfigRoutes(this)
            installGatewayRoutes(this)
            installMaintenanceRoutes(this)
        }
    }

    private fun installStatusRoutes(route: Route) {
        route.get("/status") {
            val params = mutableMapOf("json" to "true")
            call.request.queryParameters["deep"]?.let { params["deep"] = it }
            call.request.queryParameters["usage"]?.let { params["usage"] = it }
            respondJson(call, engineProxy.send("status", params))
        }
    }

    private fun installSessionRoutes(route: Route) {
        route.get("/sessions") {
            val params = mutableMapOf("json" to "true")
            call.request.queryParameters["active_minutes"]?.let {
                params["active_minutes"] = it
            }
            call.request.queryParameters["verbose"]?.let { params["verbose"] = it }
            respondJson(call, engineProxy.send("sessions_list", params))
        }
        route.delete("/sessions/cleanup") {
            val params = mutableMapOf<String, String>()
            call.request.queryParameters["older_than_minutes"]?.let {
                params["older_than_minutes"] = it
            }
            respondJson(call, engineProxy.send("sessions_cleanup", params))
        }
    }

    private fun installMemoryRoutes(route: Route) {
        installMemoryQueryRoutes(route)
        installMemoryCategoryRoutes(route)
    }

    private fun installMemoryQueryRoutes(route: Route) {
        route.get("/memory/categories") {
            respondJson(
                call,
                engineProxy.send("memory_categories_list", mapOf("json" to "true")),
            )
        }
        route.get("/memory/search") {
            val query = call.request.queryParameters["query"]
            if (query.isNullOrBlank()) {
                respondError(call, HttpStatusCode.BadRequest, "missing query parameter")
                return@get
            }
            val params = mutableMapOf("query" to query)
            call.request.queryParameters["top_k"]?.let { params["top_k"] = it }
            respondJson(call, engineProxy.send("memory_search", params))
        }
        route.post("/memory/facts") {
            val parsed = parseJsonBody(call.receiveText())
            val category = parsed["category"]
            val content = parsed["content"]
            if (category.isNullOrBlank() || content.isNullOrBlank()) {
                respondError(call, HttpStatusCode.BadRequest, "missing category or content")
                return@post
            }
            respondJson(
                call,
                engineProxy.send(
                    "memory_facts_add",
                    mapOf("category" to category, "content" to content),
                ),
            )
        }
        route.post("/memory/consolidate") {
            val parsed = parseJsonBody(call.receiveText())
            val params = mutableMapOf<String, String>()
            parsed["date"]?.let { params["date"] = it }
            parsed["force"]?.let { params["force"] = it }
            respondJson(call, engineProxy.send("memory_consolidate", params))
        }
    }

    private fun installMemoryCategoryRoutes(route: Route) {
        route.delete("/memory/categories/{name}") {
            val name = requirePathParam(call, "name") ?: return@delete
            respondJson(
                call,
                engineProxy.send("memory_categories_delete", mapOf("name" to name)),
            )
        }
        route.put("/memory/categories/{name}/rename") {
            val name = requirePathParam(call, "name") ?: return@put
            val newName = parseJsonBody(call.receiveText())["new_name"]
            if (newName.isNullOrBlank()) {
                respondError(call, HttpStatusCode.BadRequest, "missing new_name")
                return@put
            }
            respondJson(
                call,
                engineProxy.send(
                    "memory_categories_rename",
                    mapOf("old_name" to name, "new_name" to newName),
                ),
            )
        }
        route.post("/memory/categories/merge") {
            val parsed = parseJsonBody(call.receiveText())
            val sources = parsed["sources"]
            val target = parsed["target"]
            if (sources.isNullOrBlank() || target.isNullOrBlank()) {
                respondError(call, HttpStatusCode.BadRequest, "missing sources or target")
                return@post
            }
            respondJson(
                call,
                engineProxy.send(
                    "memory_categories_merge",
                    mapOf("sources" to sources, "target" to target),
                ),
            )
        }
    }

    private fun installScheduleRoutes(route: Route) {
        route.get("/schedule/jobs") {
            respondJson(call, engineProxy.send("schedule_list"))
        }
        route.post("/schedule/jobs") {
            respondJson(
                call,
                engineProxy.send("schedule_add", parseJsonBody(call.receiveText())),
            )
        }
        route.put("/schedule/jobs/{name}") {
            val name = requirePathParam(call, "name") ?: return@put
            val parsed = parseJsonBody(call.receiveText()).toMutableMap()
            parsed["name"] = name
            respondJson(call, engineProxy.send("schedule_edit", parsed))
        }
        route.delete("/schedule/jobs/{name}") {
            val name = requirePathParam(call, "name") ?: return@delete
            respondJson(call, engineProxy.send("schedule_remove", mapOf("name" to name)))
        }
        route.post("/schedule/jobs/{name}/enable") {
            val name = call.parameters["name"] ?: ""
            respondJson(call, engineProxy.send("schedule_enable", mapOf("name" to name)))
        }
        route.post("/schedule/jobs/{name}/disable") {
            val name = call.parameters["name"] ?: ""
            respondJson(call, engineProxy.send("schedule_disable", mapOf("name" to name)))
        }
        route.post("/schedule/jobs/{name}/run") {
            val name = call.parameters["name"] ?: ""
            respondJson(call, engineProxy.send("schedule_run", mapOf("name" to name)))
        }
        route.get("/schedule/jobs/{name}/runs") {
            val name = call.parameters["name"] ?: ""
            val params = mutableMapOf("name" to name)
            call.request.queryParameters["limit"]?.let { params["limit"] = it }
            respondJson(call, engineProxy.send("schedule_runs", params))
        }
        route.get("/schedule/status") {
            respondJson(call, engineProxy.send("schedule_status"))
        }
    }

    private fun installSkillRoutes(route: Route) {
        route.get("/skills") {
            respondJson(call, engineProxy.send("skills_list"))
        }
        route.get("/skills/validate") {
            respondJson(call, engineProxy.send("skills_validate"))
        }
    }

    private fun installConfigRoutes(route: Route) {
        route.get("/config/engine") { handleConfigGet(call, "engine.json") }
        route.get("/config/gateway") { handleConfigGet(call, "gateway.json") }
        route.put("/config/engine") { handleConfigPut(call, "engine.json") }
        route.put("/config/gateway") { handleConfigPut(call, "gateway.json") }
        route.get("/config/schema/engine") {
            call.respondText(GeneratedSchemas.ENGINE, ContentType.Application.Json)
        }
        route.get("/config/schema/gateway") {
            call.respondText(GeneratedSchemas.GATEWAY, ContentType.Application.Json)
        }
    }

    private fun installGatewayRoutes(route: Route) {
        route.get("/gateway/channels") {
            val list =
                channels.joinToString(",") { ch ->
                    """{"name":"${ch.name}","alive":${ch.isAlive()}}"""
                }
            call.respondText("""{"channels":[$list]}""", ContentType.Application.Json)
        }
        route.get("/gateway/health") {
            val count = channels.count { it.isAlive() }
            call.respondText(
                """{"status":"ok","channels":$count}""",
                ContentType.Application.Json,
            )
        }
    }

    private fun installMaintenanceRoutes(route: Route) {
        route.post("/maintenance/reindex") {
            respondJson(
                call,
                engineProxy.send("reindex", parseJsonBody(call.receiveText())),
            )
        }
    }

    // ── Helpers ──

    private fun resolveApiToken(): String? {
        val envToken = System.getenv("KLAW_API_TOKEN")
        return envToken?.ifBlank { null }
    }

    private suspend fun handleConfigGet(
        call: ApplicationCall,
        filename: String,
    ) {
        val configFile = File(paths.config, filename)
        if (!configFile.exists()) {
            respondError(call, HttpStatusCode.NotFound, "$filename not found")
            return
        }
        val sanitized = ConfigSanitizer.sanitizeJsonString(configFile.readText())
        call.respondText(sanitized, ContentType.Application.Json)
    }

    private suspend fun handleConfigPut(
        call: ApplicationCall,
        filename: String,
    ) {
        val body = call.receiveText()
        try {
            kotlinx.serialization.json.Json
                .parseToJsonElement(body)
        } catch (_: Exception) {
            respondError(call, HttpStatusCode.BadRequest, "invalid JSON")
            return
        }
        File(paths.config, filename).writeText(body)
        logger.info { "Config $filename updated via API" }
        call.respondText(
            """{"status":"ok","message":"Config saved. Restart to apply."}""",
            ContentType.Application.Json,
        )
    }

    private suspend fun requirePathParam(
        call: ApplicationCall,
        name: String,
    ): String? {
        val value = call.parameters[name]
        if (value.isNullOrBlank()) {
            respondError(call, HttpStatusCode.BadRequest, "missing $name")
            return null
        }
        return value
    }

    companion object {
        suspend fun respondJson(
            call: ApplicationCall,
            result: String,
        ) {
            val status =
                if (result.startsWith("""{"error":""")) {
                    HttpStatusCode.BadRequest
                } else {
                    HttpStatusCode.OK
                }
            call.respondText(result, ContentType.Application.Json, status)
        }

        suspend fun respondError(
            call: ApplicationCall,
            status: HttpStatusCode,
            message: String,
        ) {
            call.respondText(
                """{"error":"${status.value}","message":"$message","status":${status.value}}""",
                ContentType.Application.Json,
                status,
            )
        }

        fun parseJsonBody(body: String): Map<String, String> =
            try {
                val element =
                    kotlinx.serialization.json.Json
                        .parseToJsonElement(body)
                val obj =
                    element as? kotlinx.serialization.json.JsonObject
                        ?: return emptyMap()
                obj.mapValues { it.value.toString().trim('"') }
            } catch (_: Exception) {
                emptyMap()
            }
    }
}
