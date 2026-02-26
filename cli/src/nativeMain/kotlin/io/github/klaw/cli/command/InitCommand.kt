package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.init.EngineStarter
import io.github.klaw.cli.init.InitWizard

internal class InitCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "init") {
    override fun run() {
        InitWizard(
            requestFn = requestFn,
            readLine = { readlnOrNull() },
            printer = ::echo,
            commandRunner = { cmd -> platform.posix.system(cmd) },
            engineStarterFactory = { onTick ->
                EngineStarter(onTick = onTick)
            },
        ).run()
    }
}
