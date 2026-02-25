package io.github.klaw.common.db

import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DbModelsTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    @Test
    fun `MessageRecord round-trip`() {
        val record = MessageRecord(
            id = "msg_1",
            channel = "telegram",
            chatId = "telegram_123",
            role = "user",
            type = null,
            content = "Hello",
            metadata = null,
            createdAt = "2024-01-01T00:00:00Z",
        )
        val encoded = json.encodeToString(record)
        val decoded = json.decodeFromString<MessageRecord>(encoded)
        assertEquals(record, decoded)
        assertNull(decoded.type)
        assertNull(decoded.metadata)
    }

    @Test
    fun `MessageRecord with metadata round-trip`() {
        val record = MessageRecord(
            id = "msg_2",
            channel = "telegram",
            chatId = "telegram_123",
            role = "assistant",
            type = null,
            content = "Reply",
            metadata = """{"model":"glm/glm-5","tokensIn":10}""",
            createdAt = "2024-01-01T00:01:00Z",
        )
        val encoded = json.encodeToString(record)
        val decoded = json.decodeFromString<MessageRecord>(encoded)
        assertEquals(record, decoded)
        assertEquals("""{"model":"glm/glm-5","tokensIn":10}""", decoded.metadata)
    }

    @Test
    fun `SessionRecord round-trip`() {
        val record = SessionRecord(
            chatId = "telegram_123",
            model = "glm/glm-5",
            segmentStart = "msg_1",
            createdAt = "2024-01-01T00:00:00Z",
        )
        val encoded = json.encodeToString(record)
        val decoded = json.decodeFromString<SessionRecord>(encoded)
        assertEquals(record, decoded)
    }

    @Test
    fun `SummaryRecord round-trip`() {
        val record = SummaryRecord(
            id = 42L,
            chatId = "telegram_123",
            fromMessageId = "msg_1",
            toMessageId = "msg_100",
            filePath = "/data/summaries/telegram_123_001.md",
            createdAt = "2024-01-01T12:00:00Z",
        )
        val encoded = json.encodeToString(record)
        val decoded = json.decodeFromString<SummaryRecord>(encoded)
        assertEquals(record, decoded)
    }

    @Test
    fun `SummaryRecord with null message ids round-trip`() {
        val record = SummaryRecord(
            id = 1L,
            chatId = "telegram_123",
            fromMessageId = null,
            toMessageId = null,
            filePath = "/data/summaries/initial.md",
            createdAt = "2024-01-01T00:00:00Z",
        )
        val encoded = json.encodeToString(record)
        val decoded = json.decodeFromString<SummaryRecord>(encoded)
        assertEquals(record, decoded)
        assertNull(decoded.fromMessageId)
        assertNull(decoded.toMessageId)
    }
}
