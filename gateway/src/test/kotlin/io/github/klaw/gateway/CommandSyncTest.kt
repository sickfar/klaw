package io.github.klaw.gateway

import io.github.klaw.common.command.SlashCommand
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.channel.IncomingMessage
import io.github.klaw.gateway.channel.OutgoingMessage
import io.github.klaw.gateway.command.GatewayCommandRegistry
import io.github.klaw.gateway.pairing.ConfigFileWatcher
import io.github.klaw.gateway.pairing.InboundAllowlistService
import io.github.klaw.gateway.pairing.PairingService
import io.github.klaw.gateway.socket.EngineSocketClient
import io.github.klaw.gateway.socket.GatewayOutboundHandler
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class CommandSyncTest {
    private fun slashCommand(name: String): SlashCommand =
        object : SlashCommand {
            override val name = name
            override val description = "desc"
        }

    private open class FakeChannel(
        override val name: String,
    ) : Channel {
        val updateCalls = CopyOnWriteArrayList<List<String>>()

        override fun isAlive(): Boolean = true

        override var onBecameAlive: (suspend () -> Unit)? = null

        override suspend fun listen(onMessage: suspend (IncomingMessage) -> Unit) {}

        override suspend fun send(
            chatId: String,
            response: OutgoingMessage,
        ) {}

        override suspend fun start() {}

        override suspend fun stop() {}

        override suspend fun updateCommands(commands: List<SlashCommand>) {
            updateCalls.add(commands.map { it.name })
        }
    }

    private fun makeLifecycle(
        channels: List<Channel>,
        registry: GatewayCommandRegistry,
    ): GatewayLifecycle {
        val engineClient = mockk<EngineSocketClient>(relaxed = true)
        val outboundHandler = mockk<GatewayOutboundHandler>(relaxed = true)
        val allowlistService = mockk<InboundAllowlistService>(relaxed = true)
        val pairingService = mockk<PairingService>(relaxed = true)
        val configFileWatcher = mockk<ConfigFileWatcher>(relaxed = true)
        return GatewayLifecycle(
            channels = channels,
            engineClient = engineClient,
            outboundHandler = outboundHandler,
            allowlistService = allowlistService,
            pairingService = pairingService,
            configFileWatcher = configFileWatcher,
            commandRegistry = registry,
        )
    }

    @Test
    fun `sync pushes commands to all channels on first run`() =
        runTest {
            val registry = mockk<GatewayCommandRegistry>(relaxed = true)
            val commands = listOf(slashCommand("start"), slashCommand("model"))
            coEvery { registry.refresh() } returns commands

            val ch1 = FakeChannel("telegram")
            val ch2 = FakeChannel("discord")
            val lifecycle = makeLifecycle(listOf(ch1, ch2), registry)
            lifecycle.commandSyncScope = this

            lifecycle.startCommandSync()
            advanceTimeBy(1)

            assertEquals(1, ch1.updateCalls.size)
            assertEquals(listOf("start", "model"), ch1.updateCalls[0])
            assertEquals(1, ch2.updateCalls.size)
            assertEquals(listOf("start", "model"), ch2.updateCalls[0])

            lifecycle.commandSyncJob?.cancel()
        }

    @Test
    fun `sync skips when commands unchanged`() =
        runTest {
            val registry = mockk<GatewayCommandRegistry>(relaxed = true)
            val commands = listOf(slashCommand("start"), slashCommand("model"))
            coEvery { registry.refresh() } returns commands

            val ch = FakeChannel("telegram")
            val lifecycle = makeLifecycle(listOf(ch), registry)
            lifecycle.commandSyncScope = this

            lifecycle.startCommandSync()
            advanceTimeBy(1)

            assertEquals(1, ch.updateCalls.size)

            advanceTimeBy(60_000)
            assertEquals(1, ch.updateCalls.size)

            lifecycle.commandSyncJob?.cancel()
        }

    @Test
    fun `sync re-pushes when commands change`() =
        runTest {
            val registry = mockk<GatewayCommandRegistry>(relaxed = true)
            val first = listOf(slashCommand("start"))
            val second = listOf(slashCommand("start"), slashCommand("model"), slashCommand("help"))
            coEvery { registry.refresh() } returnsMany listOf(first, second)

            val ch = FakeChannel("telegram")
            val lifecycle = makeLifecycle(listOf(ch), registry)
            lifecycle.commandSyncScope = this

            lifecycle.startCommandSync()
            advanceTimeBy(1)

            assertEquals(1, ch.updateCalls.size)
            assertEquals(listOf("start"), ch.updateCalls[0])

            advanceTimeBy(60_000)
            assertEquals(2, ch.updateCalls.size)
            assertEquals(listOf("start", "model", "help"), ch.updateCalls[1])

            lifecycle.commandSyncJob?.cancel()
        }

    @Test
    fun `sync handles registry error gracefully and continues`() =
        runTest {
            val registry = mockk<GatewayCommandRegistry>(relaxed = true)
            val commands = listOf(slashCommand("start"), slashCommand("model"))
            coEvery { registry.refresh() } throws IOException("engine down") andThen commands

            val ch = FakeChannel("telegram")
            val lifecycle = makeLifecycle(listOf(ch), registry)
            lifecycle.commandSyncScope = this

            lifecycle.startCommandSync()
            advanceTimeBy(1)

            assertEquals(0, ch.updateCalls.size)

            advanceTimeBy(60_000)
            assertEquals(1, ch.updateCalls.size)
            assertEquals(listOf("start", "model"), ch.updateCalls[0])

            lifecycle.commandSyncJob?.cancel()
        }

    @Test
    fun `sync skips empty command list`() =
        runTest {
            val registry = mockk<GatewayCommandRegistry>(relaxed = true)
            coEvery { registry.refresh() } returns emptyList()

            val ch = FakeChannel("telegram")
            val lifecycle = makeLifecycle(listOf(ch), registry)
            lifecycle.commandSyncScope = this

            lifecycle.startCommandSync()
            advanceTimeBy(1)

            assertEquals(0, ch.updateCalls.size)

            lifecycle.commandSyncJob?.cancel()
        }

    @Test
    fun `sync continues when one channel fails`() =
        runTest {
            val registry = mockk<GatewayCommandRegistry>(relaxed = true)
            val commands = listOf(slashCommand("start"), slashCommand("model"))
            coEvery { registry.refresh() } returns commands

            val failingChannel =
                object : FakeChannel("failing") {
                    override suspend fun updateCommands(commands: List<SlashCommand>): Unit =
                        throw IOException("channel down")
                }
            val healthyChannel = FakeChannel("telegram")
            val lifecycle = makeLifecycle(listOf(failingChannel, healthyChannel), registry)
            lifecycle.commandSyncScope = this

            lifecycle.startCommandSync()
            advanceTimeBy(1)

            assertEquals(1, healthyChannel.updateCalls.size)
            assertEquals(listOf("start", "model"), healthyChannel.updateCalls[0])

            lifecycle.commandSyncJob?.cancel()
        }

    @Test
    fun `sync picks up engine commands after delayed engine start`() =
        runTest {
            val registry = mockk<GatewayCommandRegistry>(relaxed = true)
            val gatewayOnly = listOf(slashCommand("start"))
            val withEngine =
                listOf(slashCommand("start"), slashCommand("new"), slashCommand("help"), slashCommand("model"))
            coEvery { registry.refresh() } returnsMany listOf(gatewayOnly, withEngine)

            val ch = FakeChannel("telegram")
            val lifecycle = makeLifecycle(listOf(ch), registry)
            lifecycle.commandSyncScope = this

            lifecycle.startCommandSync()
            advanceTimeBy(1)

            assertEquals(1, ch.updateCalls.size)
            assertEquals(listOf("start"), ch.updateCalls[0])

            advanceTimeBy(60_000)
            assertEquals(2, ch.updateCalls.size)
            assertTrue(ch.updateCalls[1].containsAll(listOf("start", "new", "help", "model")))

            lifecycle.commandSyncJob?.cancel()
        }
}
