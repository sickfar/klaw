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
    workspaceDir: String,
) : CliktCommand(name = "memory") {
    init {
        subcommands(
            MemoryShowCommand(workspaceDir),
            MemoryEditCommand(workspaceDir),
            MemorySearchCommand(requestFn),
        )
    }

    override fun run() = Unit
}

internal class MemoryShowCommand(
    private val workspaceDir: String = KlawPaths.workspace,
) : CliktCommand(name = "show") {
    override fun run() {
        val memoryMdPath = "$workspaceDir/MEMORY.md"
        if (!fileExists(memoryMdPath)) {
            echo("MEMORY.md not found: $memoryMdPath")
            return
        }
        val content = readFileText(memoryMdPath)
        if (content != null) {
            echo(content)
        } else {
            echo("MEMORY.md not found: $memoryMdPath")
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class MemoryEditCommand(
    private val workspaceDir: String = KlawPaths.workspace,
) : CliktCommand(name = "edit") {
    override fun run() {
        val memoryMdPath = "$workspaceDir/MEMORY.md"
        val editor = getenv("EDITOR")?.toKString() ?: "vi"
        val result = system("$editor \"$memoryMdPath\"")
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
