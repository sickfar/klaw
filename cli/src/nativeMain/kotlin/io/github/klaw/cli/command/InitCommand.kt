package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.init.EngineStarter
import io.github.klaw.cli.init.InitWizard
import io.github.klaw.cli.util.CliLogger

internal class InitCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "init") {
    private val force by option("--force", help = "Reset and reinitialize (stops services, removes config)").flag()
    private val workspace by option("--workspace", help = "Path to existing workspace directory")

    override fun run() {
        CliLogger.info { "init started force=$force workspace=$workspace" }
        InitWizard(
            requestFn = requestFn,
            readLine = { readlnOrNull() },
            printer = ::echo,
            commandRunner = { cmd -> platform.posix.system(cmd) },
            engineStarterFactory = { onTick, startCommand ->
                EngineStarter(onTick = onTick, startCommand = startCommand)
            },
            force = force,
            workspacePath = workspace,
        ).run()
    }
}
