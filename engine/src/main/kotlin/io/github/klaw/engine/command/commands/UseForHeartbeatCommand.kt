package io.github.klaw.engine.command.commands

import io.github.klaw.common.config.encodeEngineConfig
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.protocol.CommandSocketMessage
import io.github.klaw.engine.command.EngineSlashCommand
import io.github.klaw.engine.session.Session
import io.github.klaw.engine.util.VT
import io.github.klaw.engine.workspace.HeartbeatRunnerFactory
import jakarta.inject.Provider
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

@Singleton
class UseForHeartbeatCommand(
    private val heartbeatRunnerFactory: Provider<HeartbeatRunnerFactory>,
) : EngineSlashCommand {
    override val name = "use-for-heartbeat"
    override val description = "Deliver heartbeat reports to this chat"

    internal var configPath: Path = Path.of(KlawPaths.config)

    @Suppress("TooGenericExceptionCaught")
    override suspend fun handle(
        msg: CommandSocketMessage,
        session: Session,
    ): String =
        withContext(Dispatchers.VT) {
            try {
                val runner =
                    heartbeatRunnerFactory.get().runner
                        ?: return@withContext "Heartbeat is disabled (interval=off). Enable it in engine.json first."
                runner.deliveryChannel = msg.channel
                runner.deliveryChatId = msg.chatId
                persistHeartbeatTarget(msg.channel, msg.chatId)
                "Heartbeat delivery set to ${msg.channel}/${msg.chatId}. Takes effect on next heartbeat run."
            } catch (e: Exception) {
                "Failed to update heartbeat config: ${e::class.simpleName}"
            }
        }

    private fun persistHeartbeatTarget(
        channel: String,
        chatId: String,
    ) {
        val configFile = configPath.resolve("engine.json")
        if (!Files.exists(configFile)) return
        val current = parseEngineConfig(Files.readString(configFile))
        val updated = current.copy(heartbeat = current.heartbeat.copy(channel = channel, injectInto = chatId))
        Files.writeString(configFile, encodeEngineConfig(updated))
    }
}
