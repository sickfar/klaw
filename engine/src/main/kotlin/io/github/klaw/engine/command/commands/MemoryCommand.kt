package io.github.klaw.engine.command.commands

import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.command.EngineSlashCommand
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.session.Session
import jakarta.inject.Singleton

@Singleton
class MemoryCommand(
    private val memoryService: MemoryService,
) : EngineSlashCommand {
    override val name = "memory"
    override val description = "Show memory categories from database"

    companion object {
        private const val MAX_CATEGORIES = 10
    }

    override suspend fun handle(
        msg: CommandSocketMessage,
        session: Session,
    ): String {
        val categories = memoryService.getTopCategories(MAX_CATEGORIES)
        if (categories.isEmpty()) {
            return "No memories stored yet."
        }

        val totalCount = memoryService.getTotalCategoryCount()
        val remaining = totalCount - categories.size

        return buildString {
            append("## Memory Map\n")
            append("Your long-term memory contains:\n")
            categories.forEachIndexed { index, cat ->
                if (index > 0) append("\n")
                append("- ${cat.name} (${cat.entryCount} entries)")
            }
            if (remaining > 0) {
                append("\n...and $remaining more categories")
            }
        }
    }
}
