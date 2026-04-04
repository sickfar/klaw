package io.github.klaw.engine.socket

import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.ContextDiagnosticsBreakdown
import io.github.klaw.engine.context.formatContextDiagnosticsText
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.session.SessionManager
import jakarta.inject.Singleton
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Singleton
class ContextDiagnoseHandler(
    private val sessionManager: SessionManager,
    private val contextBuilder: ContextBuilder,
) {
    @Suppress("ReturnCount")
    suspend fun handle(
        params: Map<String, String>,
        effectiveSessionManager: SessionManager = sessionManager,
        effectiveContextBuilder: ContextBuilder = contextBuilder,
    ): String {
        val chatId = params["chat_id"]
        val jsonOutput = params["json"]?.toBoolean() ?: false

        val session =
            if (chatId != null) {
                effectiveSessionManager.getSession(chatId)
                    ?: return """{"error":"session not found: ${escapeJson(chatId)}"}"""
            } else {
                effectiveSessionManager.getMostRecentSession()
                    ?: return """{"error":"no active sessions"}"""
            }

        val result =
            effectiveContextBuilder.buildContext(
                session = session,
                pendingMessages = listOf("(diagnostic simulation)"),
                isSubagent = false,
                includeDiagnostics = true,
            )
        val diag =
            result.diagnostics
                ?: return """{"error":"diagnostics unavailable"}"""

        return if (jsonOutput) {
            buildJson(session, diag)
        } else {
            escapeNewlines(formatContextDiagnosticsText(session, diag, result.budget, result.uncoveredMessageTokens))
        }
    }

    private fun buildJson(
        session: Session,
        diag: ContextDiagnosticsBreakdown,
    ): String {
        val obj =
            buildJsonObject {
                put("chatId", session.chatId)
                put("model", session.model)
                put("segmentStart", session.segmentStart)
                put("systemPromptTokens", diag.systemPromptTokens)
                put("systemPromptChars", diag.systemPromptChars)
                put("summaryTokens", diag.summaryTokens)
                put("summaryChars", diag.summaryChars)
                put("pendingTokens", diag.pendingTokens)
                put("pendingChars", diag.pendingChars)
                put("toolTokens", diag.toolTokens)
                put("toolChars", diag.toolChars)
                put("toolCount", diag.toolCount)
                put("overhead", diag.overhead)
                put("messageBudget", diag.messageBudget)
                put("windowMessageCount", diag.windowMessageCount)
                put("windowMessageTokens", diag.windowMessageTokens)
                put("windowMessageChars", diag.windowMessageChars)
                put("windowTokenCharRatio", diag.windowTokenCharRatio)
                put("firstMessageTime", diag.firstMessageTime)
                put("lastMessageTime", diag.lastMessageTime)
                put("summaryCount", diag.summaryCount)
                put("hasEvictedSummaries", diag.hasEvictedSummaries)
                put("coverageEnd", diag.coverageEnd)
                put("autoRagEnabled", diag.autoRagEnabled)
                put("autoRagTriggered", diag.autoRagTriggered)
                put("autoRagResultCount", diag.autoRagResultCount)
                put("compactionEnabled", diag.compactionEnabled)
                put("compactionThreshold", diag.compactionThreshold)
                put("compactionWouldTrigger", diag.compactionWouldTrigger)
                put("skillCount", diag.skillCount)
                put("inlineSkills", diag.inlineSkills)
                put(
                    "toolNames",
                    buildJsonArray {
                        diag.toolNames.forEach { add(JsonPrimitive(it)) }
                    },
                )
            }
        return Json.encodeToString(JsonObject.serializer(), obj)
    }

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private fun escapeNewlines(value: String): String = value.replace("\r", "\\r").replace("\n", "\\n")
}
