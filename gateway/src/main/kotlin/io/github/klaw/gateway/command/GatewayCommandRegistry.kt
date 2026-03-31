package io.github.klaw.gateway.command

import io.github.klaw.common.command.SlashCommand
import io.github.klaw.gateway.api.EngineApiProxy
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

@Serializable
private data class CommandDto(
    val name: String,
    val description: String,
)

@Serializable
private data class EngineCommandsResponse(
    val commands: List<CommandDto> = emptyList(),
)

@Singleton
class GatewayCommandRegistry(
    private val engineApi: EngineApiProxy,
    private val gatewayCommands: List<GatewaySlashCommand>,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    @Volatile
    private var cachedEngineCommands: List<CommandDto> = emptyList()

    @Volatile
    private var cachedAllSlashCommands: List<SlashCommand> = emptyList()

    @Volatile
    private var lastRefreshMs: Long = 0

    private val refreshIntervalMs: Long = REFRESH_INTERVAL_MS

    suspend fun refresh(): List<SlashCommand> =
        mutex.withLock {
            doRefresh()
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun doRefresh(): List<SlashCommand> =
        try {
            val response = engineApi.send("commands_list")
            val engineCommands =
                try {
                    val parsed = json.decodeFromString<EngineCommandsResponse>(response)
                    parsed.commands
                } catch (e: kotlinx.serialization.SerializationException) {
                    logger.warn { "Failed to parse engine commands: ${e::class.simpleName}" }
                    emptyList()
                }
            cachedEngineCommands = engineCommands
            cachedAllSlashCommands = engineCommands.map { it.toSlashCommand() } + gatewayCommands
            lastRefreshMs = System.currentTimeMillis()
            logger.debug { "Refreshed commands: ${engineCommands.size} from engine, ${gatewayCommands.size} gateway" }
            cachedAllSlashCommands
        } catch (e: Exception) {
            logger.warn { "Failed to fetch commands from engine: ${e::class.simpleName}" }
            cachedEngineCommands = emptyList()
            cachedAllSlashCommands = gatewayCommands
            cachedAllSlashCommands
        }

    suspend fun allCommands(): List<SlashCommand> {
        // Check if refresh is needed (outside lock for performance)
        if (System.currentTimeMillis() - lastRefreshMs > refreshIntervalMs) {
            mutex.withLock {
                // Double-check after acquiring lock
                if (System.currentTimeMillis() - lastRefreshMs > refreshIntervalMs) {
                    doRefresh()
                }
                // Return inside lock to ensure consistency
                return cachedAllSlashCommands
            }
        }
        return cachedAllSlashCommands
    }

    suspend fun findCommand(name: String): SlashCommand? {
        val all = allCommands()
        return all.find { it.name == name }
    }

    fun findGatewayCommand(name: String): GatewaySlashCommand? = gatewayCommands.find { it.name == name }

    private fun CommandDto.toSlashCommand(): SlashCommand =
        object : SlashCommand {
            override val name = this@toSlashCommand.name
            override val description = this@toSlashCommand.description
        }

    companion object {
        private const val REFRESH_INTERVAL_MS = 60_000L
    }
}
