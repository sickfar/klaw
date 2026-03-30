package io.github.klaw.engine.command.commands

import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.command.EngineSlashCommand
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.util.VT
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

@Singleton
class MemoryCommand(
    internal val workspacePath: Path = Path.of(KlawPaths.workspace),
) : EngineSlashCommand {
    override val name = "memory"
    override val description = "Show MEMORY.md from workspace"

    override suspend fun handle(
        msg: CommandSocketMessage,
        session: Session,
    ): String =
        withContext(Dispatchers.VT) {
            val f = workspacePath.resolve("MEMORY.md")
            if (Files.exists(f)) Files.readString(f).trim() else "No MEMORY.md found in workspace."
        }
}
