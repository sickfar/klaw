package io.github.klaw.engine.command

import io.github.klaw.common.command.SlashCommand
import io.github.klaw.common.config.EngineConfig
import jakarta.inject.Singleton

@Singleton
class EngineCommandRegistry(
    private val commands: List<EngineSlashCommand>,
    private val config: EngineConfig,
) {
    fun allCommands(): List<SlashCommand> = commands + config.commands
}
