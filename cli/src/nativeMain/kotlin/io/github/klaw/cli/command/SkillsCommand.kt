package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger

internal class SkillsCommand(
    requestFn: EngineRequest,
) : CliktCommand(name = "skills") {
    init {
        subcommands(SkillsValidateCommand(requestFn))
    }

    override fun run() = Unit
}

internal class SkillsValidateCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "validate") {
    override fun run() {
        CliLogger.debug { "skills validate" }
        try {
            echo(requestFn("skills_validate", emptyMap()))
        } catch (_: EngineNotRunningException) {
            CliLogger.error { "engine not running" }
            echo("Engine is not running. Start it with: systemctl --user start klaw-engine")
        }
    }
}
