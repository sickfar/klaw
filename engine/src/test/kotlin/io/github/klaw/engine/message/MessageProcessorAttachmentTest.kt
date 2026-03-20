package io.github.klaw.engine.message

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.protocol.Attachment
import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.engine.db.KlawDatabase
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MessageProcessorAttachmentTest {
    private lateinit var db: KlawDatabase
    private lateinit var messageRepository: MessageRepository

    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setUp() {
        val driver = JdbcSqliteDriver("jdbc:sqlite:")
        KlawDatabase.Schema.create(driver)
        db = KlawDatabase(driver)
        messageRepository = MessageRepository(db)
    }

    @Test
    fun `persistInboundMessage with attachments sets type multimodal and metadata`() =
        runBlocking {
            val msg =
                InboundSocketMessage(
                    id = "msg-1",
                    channel = "telegram",
                    chatId = "chat-1",
                    content = "Look at this photo",
                    ts = "2024-01-01T00:00:00Z",
                    attachments =
                        listOf(
                            Attachment(
                                path = "/data/photos/img.jpg",
                                mimeType = "image/jpeg",
                                originalName = "img.jpg",
                            ),
                        ),
                )

            val type = if (msg.attachments.isNotEmpty()) "multimodal" else "text"
            val metadata =
                if (msg.attachments.isNotEmpty()) {
                    Json.encodeToString(
                        AttachmentMetadata.serializer(),
                        AttachmentMetadata(
                            attachments = msg.attachments.map { AttachmentRef(it.path, it.mimeType) },
                        ),
                    )
                } else {
                    null
                }

            messageRepository.save(
                id = msg.id,
                channel = msg.channel,
                chatId = msg.chatId,
                role = "user",
                type = type,
                content = msg.content,
                metadata = metadata,
                tokens = 10,
            )

            val rows = messageRepository.getAllMessagesInSegment("chat-1", "2000-01-01T00:00:00Z")
            assertEquals(1, rows.size)
            assertEquals("multimodal", rows[0].type)

            val parsed = json.decodeFromString<AttachmentMetadata>(rows[0].metadata!!)
            assertEquals(1, parsed.attachments.size)
            assertEquals("/data/photos/img.jpg", parsed.attachments[0].path)
            assertEquals("image/jpeg", parsed.attachments[0].mimeType)
        }

    @Test
    fun `persistInboundMessage without attachments sets type text and no metadata`() =
        runBlocking {
            val msg =
                InboundSocketMessage(
                    id = "msg-2",
                    channel = "telegram",
                    chatId = "chat-1",
                    content = "Plain text message",
                    ts = "2024-01-01T00:00:00Z",
                )

            val type = if (msg.attachments.isNotEmpty()) "multimodal" else "text"
            val metadata: String? =
                if (msg.attachments.isNotEmpty()) {
                    Json.encodeToString(
                        AttachmentMetadata.serializer(),
                        AttachmentMetadata(
                            attachments = msg.attachments.map { AttachmentRef(it.path, it.mimeType) },
                        ),
                    )
                } else {
                    null
                }

            messageRepository.save(
                id = msg.id,
                channel = msg.channel,
                chatId = msg.chatId,
                role = "user",
                type = type,
                content = msg.content,
                metadata = metadata,
                tokens = 5,
            )

            val rows = messageRepository.getAllMessagesInSegment("chat-1", "2000-01-01T00:00:00Z")
            assertEquals(1, rows.size)
            assertEquals("text", rows[0].type)
            assertNull(rows[0].metadata)
        }

    @Test
    fun `message with multiple attachments stores all refs in metadata`() =
        runBlocking {
            val msg =
                InboundSocketMessage(
                    id = "msg-3",
                    channel = "telegram",
                    chatId = "chat-1",
                    content = "Multiple photos",
                    ts = "2024-01-01T00:00:00Z",
                    attachments =
                        listOf(
                            Attachment(path = "/data/img1.jpg", mimeType = "image/jpeg"),
                            Attachment(path = "/data/img2.png", mimeType = "image/png"),
                            Attachment(path = "/data/doc.webp", mimeType = "image/webp"),
                        ),
                )

            val metadata =
                AttachmentMetadata(
                    attachments = msg.attachments.map { AttachmentRef(it.path, it.mimeType) },
                )

            messageRepository.save(
                id = msg.id,
                channel = msg.channel,
                chatId = msg.chatId,
                role = "user",
                type = "multimodal",
                content = msg.content,
                metadata = Json.encodeToString(AttachmentMetadata.serializer(), metadata),
                tokens = 10,
            )

            val rows = messageRepository.getAllMessagesInSegment("chat-1", "2000-01-01T00:00:00Z")
            assertEquals(1, rows.size)
            assertNotNull(rows[0].metadata)

            val parsed = json.decodeFromString<AttachmentMetadata>(rows[0].metadata!!)
            assertEquals(3, parsed.attachments.size)
            assertEquals("image/jpeg", parsed.attachments[0].mimeType)
            assertEquals("image/png", parsed.attachments[1].mimeType)
            assertEquals("image/webp", parsed.attachments[2].mimeType)
        }
}
