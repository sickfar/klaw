package io.github.klaw.engine.command

import io.github.klaw.common.command.SlashCommand
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.session.Session

interface EngineSlashCommand : SlashCommand {
    suspend fun handle(
        msg: CommandSocketMessage,
        session: Session,
    ): String
}
