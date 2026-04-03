package io.github.klaw.engine.context

import io.github.klaw.engine.session.Session

private const val NUM_WIDTH = 6
private const val CHARS_WIDTH = 9

@Suppress("LongMethod")
fun formatContextDiagnosticsText(
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
