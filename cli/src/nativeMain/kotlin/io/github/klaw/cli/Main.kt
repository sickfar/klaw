package io.github.klaw.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.command.ChatCommand
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
import io.github.klaw.cli.init.checkTcpPort
import io.github.klaw.cli.socket.EngineSocketClient
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.runCommandOutput
import io.github.klaw.common.paths.KlawPaths

/** Type alias for delegated engine commands — (command, params) → JSON response */
internal typealias EngineRequest = (command: String, params: Map<String, String>) -> String

internal fun defaultEngineRequest(): EngineRequest =
    { cmd, params ->
        EngineSocketClient().request(cmd, params)
    }

@Suppress("LongParameterList")
class KlawCli(
    requestFn: EngineRequest = defaultEngineRequest(),
    conversationsDir: String = KlawPaths.conversations,
    engineChecker: () -> Boolean = { checkTcpPort(KlawPaths.engineHost, KlawPaths.enginePort) },
    configDir: String = KlawPaths.config,
    modelsDir: String = KlawPaths.models,
    workspaceDir: String = KlawPaths.workspace,
    commandRunner: (String) -> Int = { cmd -> platform.posix.system(cmd) },
    doctorCommandOutput: (String) -> String? = ::runCommandOutput,
    private val logDir: String = KlawPaths.logs,
) : CliktCommand(name = "klaw") {
    private val verbose by option("-v", "--verbose", help = "Enable debug logging")
        .flag(default = false)

    init {
        subcommands(
            InitCommand(requestFn),
            ChatCommand(configDir),
            StatusCommand(requestFn),
            SessionsCommand(requestFn),
            ReindexCommand(requestFn),
            LogsCommand(conversationsDir),
            ScheduleCommand(requestFn),
            MemoryCommand(requestFn, workspaceDir),
            DoctorCommand(configDir, engineChecker, modelsDir, workspaceDir, doctorCommandOutput),
            ConfigCommand(configDir),
            IdentityCommand(workspaceDir, commandRunner),
            EngineCommand(commandRunner, configDir),
            GatewayCommand(commandRunner, configDir),
            StopCommand(commandRunner, configDir),
        )
    }

    override fun run() {
        val level = if (verbose) CliLogger.Level.DEBUG else CliLogger.Level.INFO
        CliLogger.init(logDir = logDir, level = level)
        CliLogger.info { "klaw ${currentContext.invokedSubcommand ?: ""}" }
    }
}

/** Moves `-v` / `--verbose` to the front so Clikt's parent command sees it regardless of position. */
internal fun hoistVerboseFlag(args: Array<String>): Array<String> {
    val hasVerbose = args.any { it == "-v" || it == "--verbose" }
    if (!hasVerbose) return args
    val filtered = args.filter { it != "-v" && it != "--verbose" }
    return (listOf("--verbose") + filtered).toTypedArray()
}

fun main(args: Array<String>) {
    // Allow -v/--verbose anywhere (e.g. "klaw init -v" instead of only "klaw -v init")
    val normalizedArgs = hoistVerboseFlag(args)
    try {
        KlawCli().main(normalizedArgs)
    } catch (
        @Suppress("TooGenericExceptionCaught")
        e: Exception,
    ) {
        CliLogger.error { "command failed: ${e::class.simpleName}" }
        throw e
    } finally {
        CliLogger.close()
    }
}
