package io.github.klaw.gateway

import io.github.klaw.common.config.AllowedChat
import io.github.klaw.common.config.ChannelsConfig
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.TelegramConfig
import io.github.klaw.common.protocol.SocketMessage
import io.github.klaw.gateway.channel.IncomingMessage
import io.github.klaw.gateway.pairing.ConfigFileWatcher
import io.github.klaw.gateway.pairing.InboundAllowlistService
import io.github.klaw.gateway.pairing.PairingCodeResult
import io.github.klaw.gateway.pairing.PairingService
import io.github.klaw.gateway.pairing.PairingStatus
import io.github.klaw.gateway.socket.EngineSocketClient
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.runs
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.time.Clock

class GatewayLifecycleInboundTest {
    @Suppress("LongParameterList")
    private fun makeIncoming(
        channel: String = "telegram",
        chatId: String = "telegram_123",
        content: String = "hello",
        userId: String? = "user1",
        isCommand: Boolean = false,
        commandName: String? = null,
    ) = IncomingMessage(
        id = "msg-1",
        channel = channel,
        chatId = chatId,
        content = content,
        ts = Clock.System.now(),
        userId = userId,
        isCommand = isCommand,
        commandName = commandName,
    )

    @Test
    fun `allowed message forwarded to engine`() =
        runBlocking {
            val allowlistService = mockk<InboundAllowlistService>()
            every { allowlistService.isAllowed("telegram", "telegram_123", "user1") } returns true

            val engineClient = mockk<EngineSocketClient>(relaxed = true)
            val sentMessages = mutableListOf<SocketMessage>()
            every { engineClient.send(capture(sentMessages)) } returns true

            val incoming = makeIncoming()
            val callback =
                GatewayLifecycle.buildInboundCallback(
                    allowlistService = allowlistService,
                    pairingService = mockk(relaxed = true),
                    configFileWatcher = mockk(relaxed = true),
                    engineClient = engineClient,
                    replyFn = { _, _ -> },
                )
            callback(incoming)

            coVerify(exactly = 1) { engineClient.send(any()) }
        }

    @Test
    fun `denied message not forwarded and reply sent`() =
        runBlocking {
            val allowlistService = mockk<InboundAllowlistService>()
            every { allowlistService.isAllowed("telegram", "telegram_123", "user1") } returns false

            val engineClient = mockk<EngineSocketClient>(relaxed = true)
            val replies = mutableListOf<Pair<String, String>>()

            val incoming = makeIncoming()
            val callback =
                GatewayLifecycle.buildInboundCallback(
                    allowlistService = allowlistService,
                    pairingService = mockk(relaxed = true),
                    configFileWatcher = mockk(relaxed = true),
                    engineClient = engineClient,
                    replyFn = { chatId, msg -> replies.add(chatId to msg) },
                )
            callback(incoming)

            coVerify(exactly = 0) { engineClient.send(any()) }
            assert(replies.size == 1)
            assert(replies[0].second.contains("Not paired"))
        }

    @Test
    fun `start command for already paired chat replies already paired`() =
        runBlocking {
            val allowlistService = mockk<InboundAllowlistService>()
            every {
                allowlistService.isStartAllowed("telegram", "telegram_123", "user1")
            } returns PairingStatus.AlreadyPaired

            val engineClient = mockk<EngineSocketClient>(relaxed = true)
            val replies = mutableListOf<Pair<String, String>>()

            val incoming = makeIncoming(isCommand = true, commandName = "start")
            val callback =
                GatewayLifecycle.buildInboundCallback(
                    allowlistService = allowlistService,
                    pairingService = mockk(relaxed = true),
                    configFileWatcher = mockk(relaxed = true),
                    engineClient = engineClient,
                    replyFn = { chatId, msg -> replies.add(chatId to msg) },
                )
            callback(incoming)

            coVerify(exactly = 0) { engineClient.send(any()) }
            assert(replies.size == 1)
            assert(replies[0].second.contains("Already paired"))
        }

    @Test
    fun `start command for new chat generates pairing code`() =
        runBlocking {
            val allowlistService = mockk<InboundAllowlistService>()
            every {
                allowlistService.isStartAllowed("telegram", "telegram_123", "user1")
            } returns PairingStatus.NewChat

            val pairingService = mockk<PairingService>()
            every {
                pairingService.generateCode("telegram", "telegram_123", "user1")
            } returns PairingCodeResult.Success("ABC123")
            every { pairingService.hasPendingRequests() } returns true

            val configFileWatcher = mockk<ConfigFileWatcher>(relaxed = true)
            val engineClient = mockk<EngineSocketClient>(relaxed = true)
            val replies = mutableListOf<Pair<String, String>>()

            val incoming = makeIncoming(isCommand = true, commandName = "start")
            val callback =
                GatewayLifecycle.buildInboundCallback(
                    allowlistService = allowlistService,
                    pairingService = pairingService,
                    configFileWatcher = configFileWatcher,
                    engineClient = engineClient,
                    replyFn = { chatId, msg -> replies.add(chatId to msg) },
                )
            callback(incoming)

            coVerify(exactly = 0) { engineClient.send(any()) }
            assert(replies.size == 1)
            assert(replies[0].second.contains("ABC123"))
        }

    @Test
    fun `start command rate limited replies accordingly`() =
        runBlocking {
            val allowlistService = mockk<InboundAllowlistService>()
            every { allowlistService.isStartAllowed("telegram", "telegram_123", "user1") } returns PairingStatus.NewChat

            val pairingService = mockk<PairingService>()
            every {
                pairingService.generateCode("telegram", "telegram_123", "user1")
            } returns PairingCodeResult.RateLimited

            val replies = mutableListOf<Pair<String, String>>()
            val callback =
                GatewayLifecycle.buildInboundCallback(
                    allowlistService = allowlistService,
                    pairingService = pairingService,
                    configFileWatcher = mockk(relaxed = true),
                    engineClient = mockk(relaxed = true),
                    replyFn = { chatId, msg -> replies.add(chatId to msg) },
                )
            callback(makeIncoming(isCommand = true, commandName = "start"))

            assert(replies.size == 1)
            assert(replies[0].second.contains("wait"))
        }

    @Test
    fun `console start command replies already paired`() =
        runBlocking {
            val allowlistService = mockk<InboundAllowlistService>()
            every { allowlistService.isStartAllowed("console", any(), any()) } returns PairingStatus.AlreadyPaired

            val replies = mutableListOf<Pair<String, String>>()
            val callback =
                GatewayLifecycle.buildInboundCallback(
                    allowlistService = allowlistService,
                    pairingService = mockk(relaxed = true),
                    configFileWatcher = mockk(relaxed = true),
                    engineClient = mockk(relaxed = true),
                    replyFn = { chatId, msg -> replies.add(chatId to msg) },
                )
            callback(
                makeIncoming(
                    channel = "console",
                    chatId = "console_default",
                    isCommand = true,
                    commandName = "start",
                ),
            )

            assert(replies.size == 1)
            assert(replies[0].second.contains("Already paired"))
        }

    @Test
    fun `confirmation message sent when config changes after pairing`() =
        runBlocking {
            val allowlistService = mockk<InboundAllowlistService>(relaxed = true)
            every { allowlistService.isStartAllowed("telegram", "telegram_123", "user1") } returns PairingStatus.NewChat
            every { allowlistService.isChatAllowed("telegram", "telegram_123") } returns true

            val pairingService = mockk<PairingService>()
            every {
                pairingService.generateCode("telegram", "telegram_123", "user1")
            } returns PairingCodeResult.Success("ABC123")
            every { pairingService.hasPendingRequests() } returns true

            val capturedCallback = slot<(GatewayConfig) -> Unit>()
            val configFileWatcher = mockk<ConfigFileWatcher>()
            every { configFileWatcher.startWatching(capture(capturedCallback)) } just runs

            val replies = mutableListOf<Pair<String, String>>()
            val incoming = makeIncoming(isCommand = true, commandName = "start")
            val callback =
                GatewayLifecycle.buildInboundCallback(
                    allowlistService = allowlistService,
                    pairingService = pairingService,
                    configFileWatcher = configFileWatcher,
                    engineClient = mockk(relaxed = true),
                    replyFn = { chatId, msg -> replies.add(chatId to msg) },
                )
            callback(incoming)

            assert(replies.size == 1 && replies[0].second.contains("ABC123")) {
                "Expected pairing code reply, got: $replies"
            }

            // Simulate `klaw pair` writing gateway.json with the chatId added
            val updatedConfig =
                GatewayConfig(
                    channels =
                        ChannelsConfig(
                            telegram =
                                TelegramConfig(
                                    token = "t",
                                    allowedChats = listOf(AllowedChat("telegram_123", listOf("user1"))),
                                ),
                        ),
                )
            capturedCallback.captured.invoke(updatedConfig)

            assert(replies.size == 2) { "Expected confirmation message, got: $replies" }
            assert(replies[1].first == "telegram_123") { "Expected chatId telegram_123, got: ${replies[1].first}" }
            assert(replies[1].second.contains("Pairing successful") || replies[1].second.contains("success")) {
                "Expected success message, got: ${replies[1].second}"
            }
            verify { allowlistService.reload(updatedConfig) }
        }

    @Test
    fun `confirmation not sent again when watcher callback fires a second time`() =
        runBlocking {
            val allowlistService = mockk<InboundAllowlistService>(relaxed = true)
            every { allowlistService.isStartAllowed("telegram", "telegram_123", "user1") } returns PairingStatus.NewChat
            every { allowlistService.isChatAllowed("telegram", "telegram_123") } returns true

            val pairingService = mockk<PairingService>()
            every {
                pairingService.generateCode("telegram", "telegram_123", "user1")
            } returns PairingCodeResult.Success("DEF456")
            every { pairingService.hasPendingRequests() } returns true

            val capturedCallback = slot<(GatewayConfig) -> Unit>()
            val configFileWatcher = mockk<ConfigFileWatcher>()
            every { configFileWatcher.startWatching(capture(capturedCallback)) } just runs

            val replies = mutableListOf<Pair<String, String>>()
            val incoming = makeIncoming(isCommand = true, commandName = "start")
            val callback =
                GatewayLifecycle.buildInboundCallback(
                    allowlistService = allowlistService,
                    pairingService = pairingService,
                    configFileWatcher = configFileWatcher,
                    engineClient = mockk(relaxed = true),
                    replyFn = { chatId, msg -> replies.add(chatId to msg) },
                )
            callback(incoming)

            val updatedConfig =
                GatewayConfig(
                    channels =
                        ChannelsConfig(
                            telegram =
                                TelegramConfig(
                                    token = "t",
                                    allowedChats = listOf(AllowedChat("telegram_123", listOf("user1"))),
                                ),
                        ),
                )
            capturedCallback.captured.invoke(updatedConfig) // first fire — confirmation sent
            capturedCallback.captured.invoke(updatedConfig) // second fire — must NOT send again

            assert(replies.size == 2) {
                "Expected no duplicate confirmation (1 code + 1 confirmation), got: $replies"
            }
        }

    @Test
    fun `no confirmation sent when config changes but chatId not added`() =
        runBlocking {
            val allowlistService = mockk<InboundAllowlistService>(relaxed = true)
            every { allowlistService.isStartAllowed("telegram", "telegram_123", "user1") } returns PairingStatus.NewChat
            every { allowlistService.isChatAllowed("telegram", "telegram_123") } returns false

            val pairingService = mockk<PairingService>()
            every {
                pairingService.generateCode("telegram", "telegram_123", "user1")
            } returns PairingCodeResult.Success("XYZ999")
            every { pairingService.hasPendingRequests() } returns true

            val capturedCallback = slot<(GatewayConfig) -> Unit>()
            val configFileWatcher = mockk<ConfigFileWatcher>()
            every { configFileWatcher.startWatching(capture(capturedCallback)) } just runs

            val replies = mutableListOf<Pair<String, String>>()
            val incoming = makeIncoming(isCommand = true, commandName = "start")
            val callback =
                GatewayLifecycle.buildInboundCallback(
                    allowlistService = allowlistService,
                    pairingService = pairingService,
                    configFileWatcher = configFileWatcher,
                    engineClient = mockk(relaxed = true),
                    replyFn = { chatId, msg -> replies.add(chatId to msg) },
                )
            callback(incoming)

            assert(replies.size == 1 && replies[0].second.contains("XYZ999")) {
                "Expected pairing code reply, got: $replies"
            }

            // Simulate config change that does NOT include our chatId (e.g., unrelated edit)
            val unrelatedConfig =
                GatewayConfig(
                    channels =
                        ChannelsConfig(
                            telegram = TelegramConfig(token = "t", allowedChats = emptyList()),
                        ),
                )
            capturedCallback.captured.invoke(unrelatedConfig)

            assert(replies.size == 1) {
                "Expected no confirmation message after unrelated config change, got: $replies"
            }
        }
}
