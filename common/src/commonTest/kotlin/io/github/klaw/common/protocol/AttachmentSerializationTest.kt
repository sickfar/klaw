package io.github.klaw.common.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AttachmentSerializationTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Test
    fun `Attachment serialization round-trip`() {
        val attachment = Attachment(path = "/img/cat.png", mimeType = "image/png", originalName = "cat.png")
        val encoded = json.encodeToString(attachment)
        val decoded = json.decodeFromString<Attachment>(encoded)
        assertEquals(attachment, decoded)
        assertEquals("/img/cat.png", decoded.path)
        assertEquals("image/png", decoded.mimeType)
        assertEquals("cat.png", decoded.originalName)
    }

    @Test
    fun `Attachment with null originalName round-trip`() {
        val attachment = Attachment(path = "/img/photo.jpg", mimeType = "image/jpeg")
        val encoded = json.encodeToString(attachment)
        val decoded = json.decodeFromString<Attachment>(encoded)
        assertEquals(attachment, decoded)
        assertEquals(null, decoded.originalName)
    }

    @Test
    fun `InboundSocketMessage with empty attachments - backward compatible`() {
        // Old JSON without attachments field should parse correctly
        val raw =
            """{"type":"inbound","id":"1","channel":"tg","chatId":"123","content":"hi","ts":"2024-01-01T00:00:00Z"}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<InboundSocketMessage>(decoded)
        assertTrue(decoded.attachments.isEmpty())
    }

    @Test
    fun `InboundSocketMessage with attachments present - deserialization`() {
        val raw =
            """{"type":"inbound","id":"1","channel":"tg","chatId":"123","content":"hi","ts":"2024-01-01T00:00:00Z",""" +
                """"attachments":[{"path":"/img/cat.png","mimeType":"image/png","originalName":"cat.png"}]}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<InboundSocketMessage>(decoded)
        assertEquals(1, decoded.attachments.size)
        assertEquals("/img/cat.png", decoded.attachments[0].path)
        assertEquals("image/png", decoded.attachments[0].mimeType)
        assertEquals("cat.png", decoded.attachments[0].originalName)
    }

    @Test
    fun `InboundSocketMessage with attachments round-trip`() {
        val msg =
            InboundSocketMessage(
                id = "msg_1",
                channel = "telegram",
                chatId = "telegram_123",
                content = "Look at this",
                ts = "2024-01-01T00:00:00Z",
                attachments =
                    listOf(
                        Attachment(path = "/img/a.png", mimeType = "image/png", originalName = "a.png"),
                        Attachment(path = "/img/b.jpg", mimeType = "image/jpeg"),
                    ),
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<InboundSocketMessage>(decoded)
        assertEquals(2, decoded.attachments.size)
        assertEquals("a.png", decoded.attachments[0].originalName)
        assertEquals(null, decoded.attachments[1].originalName)
    }

    @Test
    fun `ChatFrame with null attachments - backward compatible`() {
        val raw = """{"type":"user","content":"hi"}"""
        val decoded = json.decodeFromString<ChatFrame>(raw)
        assertEquals("user", decoded.type)
        assertEquals("hi", decoded.content)
        assertEquals(null, decoded.attachments)
    }

    @Test
    fun `ChatFrame with attachments present - round-trip`() {
        val frame = ChatFrame(type = "user", content = "image", attachments = listOf("/img/cat.png", "/img/dog.png"))
        val encoded = json.encodeToString(frame)
        val decoded = json.decodeFromString<ChatFrame>(encoded)
        assertEquals(frame, decoded)
        assertEquals(2, decoded.attachments?.size)
        assertEquals("/img/cat.png", decoded.attachments?.get(0))
        assertEquals("/img/dog.png", decoded.attachments?.get(1))
    }

    @Test
    fun `ChatFrame with empty attachments list`() {
        val frame = ChatFrame(type = "user", content = "text", attachments = emptyList())
        val encoded = json.encodeToString(frame)
        val decoded = json.decodeFromString<ChatFrame>(encoded)
        assertEquals(emptyList(), decoded.attachments)
    }
}
