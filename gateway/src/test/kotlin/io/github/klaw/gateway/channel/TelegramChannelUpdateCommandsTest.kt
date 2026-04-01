package io.github.klaw.gateway.channel

import io.github.klaw.common.command.SlashCommand
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

class TelegramChannelUpdateCommandsTest {
    private fun makeChannel(): TelegramChannel {
        val config = GatewayConfig(channels = ChannelsConfig())
        val jsonlWriter = mockk<ConversationJsonlWriter>(relaxed = true)
        return TelegramChannel(config, jsonlWriter)
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
    fun `updateCommands calls setCommandsAction with commands`() =
        runTest {
            val channel = makeChannel()
            val captured = CopyOnWriteArrayList<List<String>>()
            channel.setCommandsAction = { cmds -> captured.add(cmds.map { it.name }) }

            val commands = listOf(slashCommand("start"), slashCommand("model"), slashCommand("help"))
            channel.updateCommands(commands)

            assertEquals(1, captured.size)
            assertEquals(listOf("start", "model", "help"), captured[0])
        }

    @Test
    fun `updateCommands handles exception gracefully`() =
        runTest {
            val channel = makeChannel()
            channel.setCommandsAction = { throw java.io.IOException("network error") }

            // Should not throw
            channel.updateCommands(listOf(slashCommand("start")))
        }

    @Test
    fun `updateCommands called multiple times works correctly`() =
        runTest {
            val channel = makeChannel()
            val callCount = AtomicInteger(0)
            channel.setCommandsAction = { callCount.incrementAndGet() }

            channel.updateCommands(listOf(slashCommand("start")))
            channel.updateCommands(listOf(slashCommand("start"), slashCommand("model")))

            assertEquals(2, callCount.get())
        }
}
