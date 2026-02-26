package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.readFileText
import io.github.klaw.common.paths.KlawPaths
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv
import platform.posix.system

internal class MemoryCommand(
    requestFn: EngineRequest,
    coreMemoryPath: String,
) : CliktCommand(name = "memory") {
    init {
        subcommands(
            MemoryShowCommand(coreMemoryPath),
            MemoryEditCommand(coreMemoryPath),
            MemorySearchCommand(requestFn),
        )
    }

    override fun run() = Unit
}

internal class MemoryShowCommand(
    private val coreMemoryPath: String = KlawPaths.coreMemory,
) : CliktCommand(name = "show") {
    override fun run() {
        if (!fileExists(coreMemoryPath)) {
            echo("Core memory not found: $coreMemoryPath")
            return
        }
        val content = readFileText(coreMemoryPath)
        if (content != null) {
            echo(content)
        } else {
            echo("Core memory not found: $coreMemoryPath")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class MemoryEditCommand(
    private val coreMemoryPath: String = KlawPaths.coreMemory,
) : CliktCommand(name = "edit") {
    override fun run() {
        val editor = getenv("EDITOR")?.toKString() ?: "vi"
        val result = system("$editor \"$coreMemoryPath\"")
        if (result != 0) {
            echo("Editor exited with code $result")
        }
    }
}

internal class MemorySearchCommand(
    private val requestFn: EngineRequest,
) : CliktCommand(name = "search") {
    private val query by argument()

    override fun run() {
        try {
            echo(requestFn("memory_search", mapOf("query" to query)))
        } catch (_: EngineNotRunningException) {
            echo("Engine is not running. Start it with: systemctl --user start klaw-engine")
        }
    }
}
