package io.github.klaw.engine.command

import io.github.klaw.common.command.SlashCommand
import io.github.klaw.common.config.EngineConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class EngineCommandRegistry(
    private val commands: List<EngineSlashCommand>,
    private val config: EngineConfig,
) {
    private val commandMap: Map<String, EngineSlashCommand> by lazy {
        val map = commands.associateBy { it.name }
        logger.debug { "Registered ${map.size} engine commands: ${map.keys}" }
        map
    }

    fun find(name: String): EngineSlashCommand? = commandMap[name]

    fun allCommands(): List<SlashCommand> = commands + config.commands
}
