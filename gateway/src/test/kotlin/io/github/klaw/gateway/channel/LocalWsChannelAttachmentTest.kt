package io.github.klaw.gateway.channel

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.gateway.jsonl.ConversationJsonlWriter
import io.micronaut.websocket.WebSocketSession
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LocalWsChannelAttachmentTest {
    @TempDir
    lateinit var tempDir: File

    private fun makeChannel(): LocalWsChannel =
        LocalWsChannel(ConversationJsonlWriter(tempDir.absolutePath), GatewayConfig())

    private fun mockSession(): WebSocketSession = mockk(relaxed = true)

    @Test
    fun `handleIncoming with attachments populates IncomingMessage attachments`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            val received = mutableListOf<IncomingMessage>()

            val listenJob = launch { channel.listen { msg -> received += msg } }

            val attachments = listOf("/data/photos/image1.png", "/data/photos/image2.jpg")
            channel.handleIncoming("check this photo", session, attachments)
            channel.stop()
            listenJob.join()

            assertEquals(1, received.size)
            val msg = received[0]
            assertEquals("check this photo", msg.content)
            assertEquals(2, msg.attachments.size)

            assertEquals("/data/photos/image1.png", msg.attachments[0].path)
            assertEquals("image/png", msg.attachments[0].mimeType)

            assertEquals("/data/photos/image2.jpg", msg.attachments[1].path)
            assertEquals("image/jpeg", msg.attachments[1].mimeType)
        }

    @Test
    fun `handleIncoming without attachments has empty attachments list - backward compat`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            val received = mutableListOf<IncomingMessage>()

            val listenJob = launch { channel.listen { msg -> received += msg } }

            channel.handleIncoming("hello", session)
            channel.stop()
            listenJob.join()

            assertEquals(1, received.size)
            assertTrue(received[0].attachments.isEmpty())
        }

    @Test
    fun `handleIncoming with empty attachments list has empty attachments`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            val received = mutableListOf<IncomingMessage>()

            val listenJob = launch { channel.listen { msg -> received += msg } }

            channel.handleIncoming("text only", session, emptyList())
            channel.stop()
            listenJob.join()

            assertEquals(1, received.size)
            assertTrue(received[0].attachments.isEmpty())
        }

    @Test
    fun `handleIncoming detects various image mime types from extension`() =
        runBlocking {
            val channel = makeChannel()
            val session = mockSession()
            val received = mutableListOf<IncomingMessage>()

            val listenJob = launch { channel.listen { msg -> received += msg } }

            val attachments =
                listOf(
                    "/data/img.jpeg",
                    "/data/img.gif",
                    "/data/img.webp",
                    "/data/img.bmp",
                    "/data/doc.pdf",
                    "/data/unknown.xyz",
                )
            channel.handleIncoming("mixed files", session, attachments)
            channel.stop()
            listenJob.join()

            val atts = received[0].attachments
            assertEquals(6, atts.size)
            assertEquals("image/jpeg", atts[0].mimeType)
            assertEquals("image/gif", atts[1].mimeType)
            assertEquals("image/webp", atts[2].mimeType)
            assertEquals("image/bmp", atts[3].mimeType)
            assertEquals("application/pdf", atts[4].mimeType)
            assertEquals("application/octet-stream", atts[5].mimeType)
        }
}
