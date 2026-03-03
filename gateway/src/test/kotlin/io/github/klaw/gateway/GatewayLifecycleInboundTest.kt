package io.github.klaw.gateway

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
import io.mockk.mockk
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
}
