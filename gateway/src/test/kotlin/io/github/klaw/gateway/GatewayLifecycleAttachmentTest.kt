package io.github.klaw.gateway

import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.SocketMessage
import io.github.klaw.gateway.channel.AttachmentInfo
import io.github.klaw.gateway.channel.IncomingMessage
import io.github.klaw.gateway.pairing.ConfigFileWatcher
import io.github.klaw.gateway.pairing.InboundAllowlistService
import io.github.klaw.gateway.pairing.PairingService
import io.github.klaw.gateway.socket.EngineSocketClient
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.test.assertIs
import kotlin.time.Clock

class GatewayLifecycleAttachmentTest {
    private fun makeIncoming(attachments: List<AttachmentInfo> = emptyList()) =
        IncomingMessage(
            id = "msg-1",
            channel = "local_ws",
            chatId = "local_ws_default",
            content = "check this image",
            ts = Clock.System.now(),
            userId = null,
            attachments = attachments,
        )

    @Test
    fun `forwardToEngine with attachments populates InboundSocketMessage attachments`() =
        runBlocking {
            val allowlistService = mockk<InboundAllowlistService>()
            every { allowlistService.isAllowed("local_ws", "local_ws_default", null) } returns true

            val engineClient = mockk<EngineSocketClient>(relaxed = true)
            val sentMessages = mutableListOf<SocketMessage>()
            every { engineClient.send(capture(sentMessages)) } returns true

            val attachments =
                listOf(
                    AttachmentInfo(path = "/data/photos/img.png", mimeType = "image/png", originalName = "photo.png"),
                    AttachmentInfo(path = "/data/photos/img.jpg", mimeType = "image/jpeg"),
                )
            val incoming = makeIncoming(attachments = attachments)

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
            val sent = sentMessages.single()
            assertIs<InboundSocketMessage>(sent)
            assertEquals(2, sent.attachments.size)
            assertEquals("/data/photos/img.png", sent.attachments[0].path)
            assertEquals("image/png", sent.attachments[0].mimeType)
            assertEquals("photo.png", sent.attachments[0].originalName)
            assertEquals("/data/photos/img.jpg", sent.attachments[1].path)
            assertEquals("image/jpeg", sent.attachments[1].mimeType)
            assertEquals(null, sent.attachments[1].originalName)
        }

    @Test
    fun `forwardToEngine without attachments sends empty attachments list - backward compat`() =
        runBlocking {
            val allowlistService = mockk<InboundAllowlistService>()
            every { allowlistService.isAllowed("local_ws", "local_ws_default", null) } returns true

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
            val sent = sentMessages.single()
            assertIs<InboundSocketMessage>(sent)
            assertTrue(sent.attachments.isEmpty())
        }
}
