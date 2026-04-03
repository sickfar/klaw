package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.command.EngineSlashCommand
import io.github.klaw.engine.context.ContextBuilder
import io.github.klaw.engine.context.formatContextDiagnosticsText
import io.github.klaw.engine.session.Session
import jakarta.inject.Singleton

@Singleton
class ContextCommand(
    private val contextBuilder: ContextBuilder,
) : EngineSlashCommand {
    override val name = "context"
    override val description = "Show context window diagnostics"

    override suspend fun handle(
        msg: CommandSocketMessage,
        session: Session,
    ): String {
        val result =
            contextBuilder.buildContext(
                session = session,
                pendingMessages = listOf("(diagnostic simulation)"),
                isSubagent = false,
                includeDiagnostics = true,
            )
        val diag = result.diagnostics ?: return "Diagnostics unavailable"
        return formatContextDiagnosticsText(session, diag, result.budget, result.uncoveredMessageTokens)
    }
}
