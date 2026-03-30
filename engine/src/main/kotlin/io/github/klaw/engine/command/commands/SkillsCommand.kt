package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.command.EngineSlashCommand
import io.github.klaw.engine.context.SkillDetail
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.context.SkillValidationReport
import io.github.klaw.engine.session.Session
import jakarta.inject.Singleton

@Singleton
class SkillsCommand(
    private val skillRegistry: SkillRegistry,
) : EngineSlashCommand {
    override val name = "skills"
    override val description = "Manage skills: /skills list | validate"

    override suspend fun handle(
        msg: CommandSocketMessage,
        session: Session,
    ): String =
        when (msg.args?.trim()) {
            "validate" -> {
                formatValidationReport(skillRegistry.validate())
            }

            "list" -> {
                skillRegistry.discover()
                formatSkillList(skillRegistry.listDetailed())
            }

            else -> {
                "Usage: /skills validate | list"
            }
        }

    private fun formatSkillList(skills: List<SkillDetail>): String {
        if (skills.isEmpty()) return "No skills loaded."
        return "Loaded skills (${skills.size}):\n" +
            skills.sortedBy { it.name }.joinToString("\n") { "- ${it.name}: ${it.description} (${it.source})" }
    }

    private fun formatValidationReport(report: SkillValidationReport): String {
        if (report.total == 0) return "No skill directories found."
        val lines =
            report.skills.map { e ->
                if (e.valid) {
                    "✓ ${e.name}: valid (${e.source})"
                } else {
                    "✗ ${e.directory}: ${e.error} (${e.source})"
                }
            }
        val skillSuffix = if (report.total != 1) "s" else ""
        val suffix = if (report.errors != 1) "s" else ""
        return (lines + "" + "${report.total} skill$skillSuffix checked, ${report.errors} error$suffix").joinToString("\n")
    }
}
