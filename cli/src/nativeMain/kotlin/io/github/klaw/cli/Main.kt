package io.github.klaw.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import io.github.klaw.cli.command.ConfigCommand
import io.github.klaw.cli.command.DoctorCommand
import io.github.klaw.cli.command.EngineCommand
import io.github.klaw.cli.command.GatewayCommand
import io.github.klaw.cli.command.IdentityCommand
import io.github.klaw.cli.command.InitCommand
import io.github.klaw.cli.command.LogsCommand
import io.github.klaw.cli.command.MemoryCommand
import io.github.klaw.cli.command.ReindexCommand
import io.github.klaw.cli.command.ScheduleCommand
import io.github.klaw.cli.command.SessionsCommand
import io.github.klaw.cli.command.StatusCommand
import io.github.klaw.cli.command.StopCommand
import io.github.klaw.cli.socket.EngineSocketClient
import io.github.klaw.common.paths.KlawPaths

/** Type alias for delegated engine commands — (command, params) → JSON response */
internal typealias EngineRequest = (command: String, params: Map<String, String>) -> String

internal fun defaultEngineRequest(): EngineRequest =
    { cmd, params ->
        EngineSocketClient().request(cmd, params)
    }

class KlawCli(
    requestFn: EngineRequest = defaultEngineRequest(),
    conversationsDir: String = KlawPaths.conversations,
    coreMemoryPath: String = KlawPaths.coreMemory,
    engineSocketPath: String = KlawPaths.engineSocket,
    configDir: String = KlawPaths.config,
    modelsDir: String = KlawPaths.models,
    workspaceDir: String = KlawPaths.workspace,
    commandRunner: (String) -> Int = { cmd -> platform.posix.system(cmd) },
) : CliktCommand(name = "klaw") {
    init {
        subcommands(
            InitCommand(requestFn),
            StatusCommand(requestFn),
            SessionsCommand(requestFn),
            ReindexCommand(requestFn),
            LogsCommand(conversationsDir),
            ScheduleCommand(requestFn),
            MemoryCommand(requestFn, coreMemoryPath),
            DoctorCommand(configDir, engineSocketPath, modelsDir),
            ConfigCommand(configDir),
            IdentityCommand(workspaceDir, commandRunner),
            EngineCommand(commandRunner),
            GatewayCommand(commandRunner),
            StopCommand(commandRunner),
        )
    }

    override fun run() = Unit
}

fun main(args: Array<String>) = KlawCli().main(args)
