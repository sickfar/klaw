package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.init.EngineStarter
import io.github.klaw.cli.init.InitWizard
import io.github.klaw.cli.util.CliLogger
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.staticCFunction
import platform.posix.SIGINT
import platform.posix.exit
import platform.posix.signal

internal class InitCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "init") {
    private val force by option("--force", help = "Reset and reinitialize (stops services, removes config)").flag()

    @OptIn(ExperimentalForeignApi::class)
    override fun run() {
        CliLogger.info { "init started force=$force" }
        signal(SIGINT, staticCFunction { _ -> exit(130) })
        InitWizard(
            requestFn = requestFn,
            readLine = { readlnOrNull() },
            printer = ::echo,
            commandRunner = { cmd -> platform.posix.system(cmd) },
            engineStarterFactory = { onTick, startCommand ->
                EngineStarter(onTick = onTick, startCommand = startCommand)
            },
            force = force,
        ).run()
    }
}
