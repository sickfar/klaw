package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import io.github.klaw.cli.util.fileExists
import io.github.klaw.common.paths.KlawPaths
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import platform.posix.getenv

internal class IdentityCommand(
    private val workspaceDir: String = KlawPaths.workspace,
    private val commandRunner: (String) -> Int = { cmd -> platform.posix.system(cmd) },
) : CliktCommand(name = "identity") {
    init {
        subcommands(IdentityEditCommand(workspaceDir, commandRunner))
    }

    override fun run() = Unit
}

@OptIn(ExperimentalForeignApi::class)
internal class IdentityEditCommand(
    private val workspaceDir: String,
    private val commandRunner: (String) -> Int,
) : CliktCommand(name = "edit") {
    override fun run() {
        val editor = getenv("EDITOR")?.toKString() ?: "vi"
        val soulPath = "$workspaceDir/SOUL.md"
        val identityPath = "$workspaceDir/IDENTITY.md"
        if (!fileExists(soulPath) && !fileExists(identityPath)) {
            echo("Workspace identity files not found — run: klaw init")
            return
        }
        if (fileExists(soulPath)) commandRunner("$editor $soulPath")
        if (fileExists(identityPath)) commandRunner("$editor $identityPath")
    }
}
