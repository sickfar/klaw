package io.github.klaw.gateway.command.commands

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.paths.KlawPathsSnapshot
import io.github.klaw.gateway.channel.Channel
import io.github.klaw.gateway.channel.IncomingMessage
import io.github.klaw.gateway.channel.OutgoingMessage
import io.github.klaw.gateway.pairing.ConfigFileWatcher
import io.github.klaw.gateway.pairing.InboundAllowlistService
import io.github.klaw.gateway.pairing.PairingService
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.time.Instant

class StartGatewayCommandTest {
    @TempDir
    lateinit var tempDir: Path

    private fun testPaths(): KlawPathsSnapshot {
        val dir = tempDir.toString()
        return KlawPathsSnapshot(
            config = dir,
            data = dir,
            state = dir,
            cache = dir,
            workspace = dir,
            enginePort = 7470,
            engineHost = "127.0.0.1",
            gatewayBuffer = "$dir/buffer.jsonl",
            engineOutboundBuffer = "$dir/engine-outbound-buffer.jsonl",
            klawDb = "$dir/klaw.db",
            schedulerDb = "$dir/scheduler.db",
            conversations = "$dir/conversations",
            summaries = "$dir/summaries",
            memory = "$dir/memory",
            skills = "$dir/skills",
            models = "$dir/models",
            logs = "$dir/logs",
            deployConf = "$dir/deploy",
            hybridDockerCompose = "$dir/docker-compose.json",
            pairingRequests = "$dir/pairing_requests.json",
        )
    }

    private fun createCommand(
        pairingService: PairingService,
        allowlistService: InboundAllowlistService,
        configFileWatcher: ConfigFileWatcher = mockk(relaxed = true),
    ): StartGatewayCommand = StartGatewayCommand(pairingService, allowlistService, configFileWatcher)

    private fun createTestMessage(
        chatId: String = "chat1",
        channel: String = "telegram",
        userId: String = "user1",
    ): IncomingMessage =
        IncomingMessage(
            id = "msg1",
            channel = channel,
            chatId = chatId,
            content = "/start",
            ts = Instant.parse("2024-01-01T00:00:00Z"),
            userId = userId,
            isCommand = true,
            commandName = "start",
            senderName = "Test User",
        )

    private fun createPairedConfig(
        chatId: String = "chat1",
        userId: String = "user1",
    ): GatewayConfig =
        GatewayConfig(
            channels =
                ChannelsConfig(
                    telegram =
                        TelegramConfig(
                            token = "test-token",
                            allowedChats =
                                listOf(
                                    AllowedChat(
                                        chatId = chatId,
                                        allowedUserIds = listOf(userId),
                                    ),
                                ),
                        ),
                ),
        )

    @Test
    fun `already paired returns already paired message`() =
        runTest {
            val pairingService = PairingService(testPaths())
            val config = createPairedConfig(chatId = "chat1", userId = "user1")
            val allowlistService = InboundAllowlistService(config)

            val channel = mockk<Channel>()
            coEvery { channel.send(any(), any()) } just Runs

            val cmd = createCommand(pairingService, allowlistService)
            val msg = createTestMessage()

            cmd.handle(msg, channel)

            coVerify { channel.send("chat1", OutgoingMessage("Already paired.")) }
        }

    @Test
    fun `new chat generates pairing code`() =
        runTest {
            val pairingService = PairingService(testPaths())
            val config = GatewayConfig(channels = ChannelsConfig())
            val allowlistService = InboundAllowlistService(config)

            val channel = mockk<Channel>()
            val capturedMessages = mutableListOf<OutgoingMessage>()
            coEvery { channel.send(any(), capture(capturedMessages)) } just Runs

            val cmd = createCommand(pairingService, allowlistService)
            val msg = createTestMessage(chatId = "new_chat", channel = "telegram")

            cmd.handle(msg, channel)

            coVerify { channel.send("new_chat", any()) }
            assertEquals(1, capturedMessages.size)
            assertTrue(capturedMessages[0].content.contains("Pairing code:"))
            assertTrue(capturedMessages[0].content.contains("klaw channels pair"))
        }

    @Test
    fun `command implements GatewaySlashCommand`() {
        val cmd = createCommand(mockk(), mockk())
        assertEquals("start", cmd.name)
        assertEquals("Pair this chat with Klaw", cmd.description)
    }
}
