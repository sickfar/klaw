package io.github.klaw.engine.command.commands

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.common.registry.ModelRegistry
import io.github.klaw.engine.command.EngineSlashCommand
import io.github.klaw.engine.session.Session
import jakarta.inject.Singleton

@Singleton
class ModelsCommand(
    private val config: EngineConfig,
) : EngineSlashCommand {
    override val name = "models"
    override val description = "List available LLM models"

    override suspend fun handle(
        msg: CommandSocketMessage,
        session: Session,
    ): String {
        val lines =
            config.models.entries.joinToString("\n") { (key, _) ->
                val budget = ModelRegistry.contextLength(key)?.toString() ?: "default"
                "  $key (context budget: $budget)"
            }
        return "Available models:\n$lines"
    }
}
