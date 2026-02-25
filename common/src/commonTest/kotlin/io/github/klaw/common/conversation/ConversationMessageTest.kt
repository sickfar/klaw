package io.github.klaw.common.conversation

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ConversationMessageTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Test
    fun `ConversationMessage with null meta round-trip`() {
        val msg =
            ConversationMessage(
                id = "msg_1",
                ts = "2024-01-01T10:00:00Z",
                role = "user",
                content = "Hello",
            )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<ConversationMessage>(encoded)
        assertEquals(msg, decoded)
        assertNull(decoded.meta)
        assertNull(decoded.type)
    }

    @Test
    fun `ConversationMessage with full meta round-trip`() {
        val msg =
            ConversationMessage(
                id = "msg_2",
                ts = "2024-01-01T10:01:00Z",
                role = "assistant",
                content = "Hi there!",
                type = null,
                meta =
                    MessageMeta(
                        channel = "telegram",
                        chatId = "telegram_123",
                        model = "glm/glm-5",
                        tokensIn = 10,
                        tokensOut = 20,
                    ),
            )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<ConversationMessage>(encoded)
        assertEquals(msg, decoded)
        val meta = assertNotNull(decoded.meta)
        assertEquals("telegram", meta.channel)
        assertEquals(10, meta.tokensIn)
    }

    @Test
    fun `ConversationMessage with type session_break round-trip`() {
        val msg =
            ConversationMessage(
                id = "msg_3",
                ts = "2024-01-01T10:02:00Z",
                role = "system",
                content = "",
                type = "session_break",
            )
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<ConversationMessage>(encoded)
        assertEquals("session_break", decoded.type)
    }

    @Test
    fun `ConversationMessage all roles round-trip`() {
        val roles = listOf("user", "assistant", "system", "tool")
        for (role in roles) {
            val msg = ConversationMessage(id = "id_$role", ts = "2024-01-01T00:00:00Z", role = role, content = "test")
            val encoded = json.encodeToString(msg)
            val decoded = json.decodeFromString<ConversationMessage>(encoded)
            assertEquals(role, decoded.role)
        }
    }

    @Test
    fun `JSONL format serialize list then split and deserialize each line`() {
        val messages =
            listOf(
                ConversationMessage("id1", "2024-01-01T10:00:00Z", "user", "Hello"),
                ConversationMessage("id2", "2024-01-01T10:01:00Z", "assistant", "Hi"),
            )
        val jsonl = messages.joinToString("\n") { json.encodeToString(it) }
        val lines = jsonl.split("\n")
        assertEquals(2, lines.size)
        val decoded = lines.map { json.decodeFromString<ConversationMessage>(it) }
        assertEquals(messages, decoded)
    }

    @Test
    fun `MessageMeta with scheduler source round-trip`() {
        val meta =
            MessageMeta(
                source = "scheduler",
                taskName = "daily_summary",
                model = "ollama/qwen3:8b",
            )
        val encoded = json.encodeToString(meta)
        val decoded = json.decodeFromString<MessageMeta>(encoded)
        assertEquals(meta, decoded)
        assertEquals("scheduler", decoded.source)
    }

    @Test
    fun `MessageMeta with tool round-trip`() {
        val meta = MessageMeta(tool = "memory_search")
        val encoded = json.encodeToString(meta)
        val decoded = json.decodeFromString<MessageMeta>(encoded)
        assertEquals("memory_search", decoded.tool)
    }
}
