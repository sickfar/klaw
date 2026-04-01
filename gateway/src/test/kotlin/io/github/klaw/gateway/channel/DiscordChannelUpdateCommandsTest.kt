package io.github.klaw.gateway.channel

import io.github.klaw.common.command.SlashCommand
import io.github.klaw.common.config.AllowedGuild
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.DiscordConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class DiscordChannelUpdateCommandsTest {
    private fun makeChannel(): DiscordChannel {
        val config =
            GatewayConfig(
                channels =
                    ChannelsConfig(
                        discord =
                            DiscordConfig(
                                enabled = true,
                                token = "test-token",
                                allowedGuilds =
                                    listOf(
                                        AllowedGuild(
                                            guildId = "111222333",
                                            allowedChannelIds = emptyList(),
                                            allowedUserIds = listOf("100"),
                                        ),
                                    ),
                            ),
                    ),
            )
        val jsonlWriter = mockk<ConversationJsonlWriter>(relaxed = true)
        return DiscordChannel(config, jsonlWriter)
    }

    private suspend fun startChannel(channel: DiscordChannel) {
        channel.buildKordAction = { _, _ -> }
        channel.selfBotId = "999"
        channel.start()
    }

    private fun slashCommand(
        name: String,
        description: String = "desc",
    ): SlashCommand =
        object : SlashCommand {
            override val name = name
            override val description = description
        }

    @Test
    fun `updateCommands calls registerCommandsAction with commands`() =
        runTest {
            val channel = makeChannel()
            val captured = CopyOnWriteArrayList<List<String>>()
            channel.registerCommandsAction = { cmds -> captured.add(cmds.map { it.name }) }
            startChannel(channel)

            val commands = listOf(slashCommand("start"), slashCommand("model"), slashCommand("help"))
            channel.updateCommands(commands)

            assertEquals(1, captured.size)
            assertEquals(listOf("start", "model", "help"), captured[0])
        }

    @Test
    fun `updateCommands handles exception gracefully`() =
        runTest {
            val channel = makeChannel()
            channel.registerCommandsAction = { throw java.io.IOException("network error") }
            startChannel(channel)

            // Should not throw
            channel.updateCommands(listOf(slashCommand("start")))
        }

    @Test
    fun `updateCommands called multiple times works correctly`() =
        runTest {
            val channel = makeChannel()
            val callCount = AtomicInteger(0)
            channel.registerCommandsAction = { callCount.incrementAndGet() }
            startChannel(channel)

            channel.updateCommands(listOf(slashCommand("start")))
            channel.updateCommands(listOf(slashCommand("start"), slashCommand("model")))

            assertEquals(2, callCount.get())
        }
}
