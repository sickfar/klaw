package io.github.klaw.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.versionOption
import io.github.klaw.cli.command.ChannelsCommand
import io.github.klaw.cli.command.ChatCommand
import io.github.klaw.cli.command.ConfigCommand
import io.github.klaw.cli.command.DoctorCommand
import io.github.klaw.cli.command.InitCommand
import io.github.klaw.cli.command.LogsCommand
import io.github.klaw.cli.command.MemoryCommand
import io.github.klaw.cli.command.PairCommand
import io.github.klaw.cli.command.ReindexCommand
import io.github.klaw.cli.command.ScheduleCommand
import io.github.klaw.cli.command.ServiceCommand
import io.github.klaw.cli.command.SessionsCommand
import io.github.klaw.cli.command.StatusCommand
import io.github.klaw.cli.command.UnpairCommand
import io.github.klaw.cli.command.UpdateCommand
import io.github.klaw.cli.init.checkTcpPort
import io.github.klaw.cli.socket.EngineSocketClient
import io.github.klaw.cli.update.GitHubReleaseClient
import io.github.klaw.cli.update.GitHubReleaseClientImpl
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.runCommandOutput
import io.github.klaw.common.paths.KlawPaths
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGINT
import platform.posix.exit
import platform.posix.signal

// SIGINT exit code: 128 + signal number (2) = 130, per POSIX convention
private const val SIGINT_EXIT_CODE = 130

/** Type alias for delegated engine commands — (command, params) → JSON response */
internal typealias EngineRequest = (command: String, params: Map<String, String>) -> String

internal fun defaultEngineRequest(): EngineRequest =
    { cmd, params ->
        EngineSocketClient().request(cmd, params)
    }

@Suppress("LongParameterList")
internal class KlawCli(
    requestFn: EngineRequest = defaultEngineRequest(),
    conversationsDir: String = KlawPaths.conversations,
    engineChecker: () -> Boolean = { checkTcpPort(KlawPaths.engineHost, KlawPaths.enginePort) },
    configDir: String = KlawPaths.config,
    modelsDir: String = KlawPaths.models,
    workspaceDir: String = KlawPaths.workspace,
    commandRunner: (String) -> Int = { cmd -> platform.posix.system(cmd) },
    releaseClient: GitHubReleaseClient = GitHubReleaseClientImpl(),
    readLine: () -> String? = ::readlnOrNull,
    doctorCommandOutput: (String) -> String? = ::runCommandOutput,
    private val logDir: String = KlawPaths.logs,
    pairingRequestsPath: String = KlawPaths.pairingRequests,
) : CliktCommand(name = "klaw") {
    private val verbose by option("-v", "--verbose", help = "Enable debug logging")
        .flag(default = false)

    init {
        versionOption(BuildConfig.VERSION)
        subcommands(
            InitCommand(requestFn),
            ChannelsCommand(requestFn),
            ChatCommand(configDir),
            StatusCommand(requestFn),
            SessionsCommand(requestFn),
            ReindexCommand(requestFn),
            LogsCommand(conversationsDir),
            ScheduleCommand(requestFn),
            MemoryCommand(requestFn),
            DoctorCommand(
                configDir,
                engineChecker,
                modelsDir,
                workspaceDir,
                doctorCommandOutput,
                commandRunner,
                requestFn,
            ),
            ConfigCommand(configDir),
            ServiceCommand(commandRunner, configDir),
            PairCommand(configDir, pairingRequestsPath),
            UnpairCommand(configDir),
            UpdateCommand(configDir, releaseClient, commandRunner, readLine),
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

@OptIn(ExperimentalForeignApi::class)
fun main(args: Array<String>) {
    signal(SIGINT, staticCFunction { _ -> exit(SIGINT_EXIT_CODE) })
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
