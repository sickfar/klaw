package io.github.klaw.engine.socket

import io.github.klaw.common.protocol.CliRequestMessage
import io.github.klaw.engine.init.InitCliHandler
import io.github.klaw.engine.maintenance.ReindexService
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.scheduler.KlawScheduler
import io.github.klaw.engine.session.SessionManager
import io.github.klaw.engine.util.VT
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Singleton
class CliCommandDispatcher(
    private val initCliHandler: InitCliHandler,
    private val sessionManager: SessionManager,
    private val klawScheduler: KlawScheduler,
    private val memoryService: MemoryService,
    private val reindexService: ReindexService,
) {
    suspend fun dispatch(request: CliRequestMessage): String =
        withContext(Dispatchers.VT) {
            when (request.command) {
                "klaw_init_status" -> {
                    initCliHandler.handleStatus()
                }

                "klaw_init_generate_identity" -> {
                    initCliHandler.handleGenerateIdentity(request.params)
                }

                "status" -> {
                    handleStatus()
                }

                "sessions" -> {
                    handleSessions()
                }

                "schedule_list" -> {
                    klawScheduler.list()
                }

                "schedule_add" -> {
                    handleScheduleAdd(request.params)
                }

                "schedule_remove" -> {
                    handleScheduleRemove(request.params)
                }

                "memory_search" -> {
                    handleMemorySearch(request.params)
                }

                "reindex" -> {
                    handleReindex()
                }

                else -> {
                    val safe = request.command.replace("\\", "\\\\").replace("\"", "\\\"")
                    """{"error":"unknown command: $safe"}"""
                }
            }
        }

    private suspend fun handleStatus(): String {
        val sessions = sessionManager.listSessions()
        return """{"status":"ok","engine":"klaw","sessions":${sessions.size}}"""
    }

    private suspend fun handleSessions(): String {
        val sessions = sessionManager.listSessions()
        return sessions.joinToString(",", "[", "]") { s ->
            """{"chatId":"${s.chatId}","model":"${s.model}"}"""
        }
    }

    @Suppress("ReturnCount")
    private suspend fun handleScheduleAdd(params: Map<String, String>): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        val cron = params["cron"] ?: return """{"error":"missing cron"}"""
        val message = params["message"] ?: return """{"error":"missing message"}"""
        val model = params["model"]
        val injectInto = params["inject_into"]
        return klawScheduler.add(name, cron, message, model, injectInto)
    }

    private suspend fun handleScheduleRemove(params: Map<String, String>): String {
        val name = params["name"] ?: return """{"error":"missing name"}"""
        return klawScheduler.remove(name)
    }

    private suspend fun handleMemorySearch(params: Map<String, String>): String {
        val query = params["query"] ?: return """{"error":"missing query"}"""
        val topK = params["top_k"]?.toIntOrNull() ?: DEFAULT_TOP_K
        return memoryService.search(query, topK)
    }

    private suspend fun handleReindex(): String {
        val lines = mutableListOf<String>()
        reindexService.reindex(onProgress = { lines += it })
        return if (lines.isEmpty()) """{"status":"ok"}""" else lines.joinToString("\n")
    }

    private companion object {
        private const val DEFAULT_TOP_K = 10
    }
}
