package io.github.klaw.engine.socket

import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.ContextDiagnosticsBreakdown
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
    suspend fun handle(params: Map<String, String>): String {
        val chatId = params["chat_id"]
        val jsonOutput = params["json"]?.toBoolean() ?: false

        val session =
            if (chatId != null) {
                sessionManager.getSession(chatId)
                    ?: return """{"error":"session not found: ${escapeJson(chatId)}"}"""
            } else {
                sessionManager.getMostRecentSession()
                    ?: return """{"error":"no active sessions"}"""
            }

        val result =
            contextBuilder.buildContext(
                session = session,
                pendingMessages = listOf("(diagnostic simulation)"),
                isSubagent = false,
                includeDiagnostics = true,
            )
        val diag = result.diagnostics!!

        return if (jsonOutput) {
            buildJson(session, diag)
        } else {
            escapeNewlines(buildText(session, diag, result.budget, result.uncoveredMessageTokens))
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

    @Suppress("LongMethod")
    private fun buildText(
        session: Session,
        diag: ContextDiagnosticsBreakdown,
        budget: Int,
        uncoveredTokens: Long,
    ): String =
        buildString {
            appendLine("Context Diagnostics")
            appendLine("===================")
            appendLine()
            appendLine("Session")
            appendLine("  Chat ID:        ${session.chatId}")
            appendLine("  Model:          ${session.model}")
            appendLine("  Segment start:  ${session.segmentStart}")
            appendLine()
            appendLine("Budget Breakdown")
            appendLine("  Total budget:  ${budget.toString().padStart(NUM_WIDTH)} tokens")
            appendLine(
                "  System prompt: ${diag.systemPromptTokens.toString().padStart(NUM_WIDTH)} tokens / " +
                    "${diag.systemPromptChars.toString().padStart(CHARS_WIDTH)} chars",
            )
            appendLine(
                "  Summaries:     ${diag.summaryTokens.toString().padStart(NUM_WIDTH)} tokens / " +
                    "${diag.summaryChars.toString().padStart(CHARS_WIDTH)} chars" +
                    "  (${diag.summaryCount} summaries)",
            )
            appendLine(
                "  Pending:       ${diag.pendingTokens.toString().padStart(NUM_WIDTH)} tokens / " +
                    "${diag.pendingChars.toString().padStart(CHARS_WIDTH)} chars  (simulated)",
            )
            appendLine(
                "  Tools:         ${diag.toolTokens.toString().padStart(NUM_WIDTH)} tokens / " +
                    "${diag.toolChars.toString().padStart(CHARS_WIDTH)} chars  (${diag.toolCount} tools)",
            )
            appendLine("  Overhead:      ${diag.overhead.toString().padStart(NUM_WIDTH)} tokens")
            appendLine("  Message budget:${diag.messageBudget.toString().padStart(NUM_WIDTH)} tokens")
            appendLine()
            appendLine("Message Window")
            appendLine("  Messages:      ${diag.windowMessageCount.toString().padStart(NUM_WIDTH)}")
            appendLine("  Tokens:        ${diag.windowMessageTokens.toString().padStart(NUM_WIDTH)}")
            appendLine("  Chars:         ${diag.windowMessageChars.toString().padStart(NUM_WIDTH)}")
            appendLine("  Ratio:         ${"%.1f".format(diag.windowTokenCharRatio)} chars/token")
            appendLine("  First:         ${diag.firstMessageTime ?: "n/a"}")
            appendLine("  Last:          ${diag.lastMessageTime ?: "n/a"}")
            appendLine()
            appendLine("Uncovered:       ${uncoveredTokens.toString().padStart(NUM_WIDTH)} tokens")
            appendLine()
            appendLine("Summaries")
            appendLine("  Count:         ${diag.summaryCount.toString().padStart(NUM_WIDTH)}")
            appendLine("  Evicted:       ${if (diag.hasEvictedSummaries) "yes" else "no"}")
            appendLine("  Coverage end:  ${diag.coverageEnd ?: "n/a"}")
            appendLine()
            appendLine("Auto-RAG")
            appendLine("  Enabled:       ${if (diag.autoRagEnabled) "yes" else "no"}")
            val autoRagStatus =
                if (diag.autoRagTriggered) "yes  (${diag.autoRagResultCount} results)" else "no"
            appendLine("  Triggered:     $autoRagStatus")
            appendLine()
            appendLine("Tools (${diag.toolCount})")
            appendLine("  ${diag.toolNames.joinToString(", ")}")
            appendLine()
            appendLine("Skills")
            appendLine("  Count:         ${diag.skillCount.toString().padStart(NUM_WIDTH)}")
            appendLine("  Mode:          ${if (diag.inlineSkills) "inline" else "listed"}")
            appendLine()
            appendLine("Compaction")
            appendLine("  Enabled:       ${if (diag.compactionEnabled) "yes" else "no"}")
            appendLine("  Threshold:     ${diag.compactionThreshold.toString().padStart(NUM_WIDTH)} tokens")
            appendLine("  Would trigger: ${if (diag.compactionWouldTrigger) "yes" else "no"}")
        }.trimEnd()

    private fun escapeJson(value: String): String =
        value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")

    private fun escapeNewlines(value: String): String = value.replace("\r", "\\r").replace("\n", "\\n")

    private companion object {
        private const val NUM_WIDTH = 6
        private const val CHARS_WIDTH = 9
    }
}
