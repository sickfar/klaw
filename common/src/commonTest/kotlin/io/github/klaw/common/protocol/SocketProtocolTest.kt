package io.github.klaw.common.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SocketProtocolTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    @Test
    fun `InboundSocketMessage serializes with correct type discriminator`() {
        val msg =
            InboundSocketMessage(
                id = "msg_1",
                channel = "telegram",
                chatId = "telegram_123",
                content = "Hello",
                ts = "2024-01-01T00:00:00Z",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        assertTrue(encoded.contains(""""type":"inbound""""), "Expected type=inbound in: $encoded")
    }

    @Test
    fun `OutboundSocketMessage serializes with correct type discriminator`() {
        val msg =
            OutboundSocketMessage(
                channel = "telegram",
                chatId = "telegram_123",
                content = "Response",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        assertTrue(encoded.contains(""""type":"outbound""""), "Expected type=outbound in: $encoded")
    }

    @Test
    fun `CommandSocketMessage serializes with correct type discriminator`() {
        val msg =
            CommandSocketMessage(
                channel = "telegram",
                chatId = "telegram_123",
                command = "status",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        assertTrue(encoded.contains(""""type":"command""""), "Expected type=command in: $encoded")
    }

    @Test
    fun `RegisterMessage serializes with correct type discriminator`() {
        val msg = RegisterMessage(client = "gateway")
        val encoded = json.encodeToString<SocketMessage>(msg)
        assertTrue(encoded.contains(""""type":"register""""), "Expected type=register in: $encoded")
    }

    @Test
    fun `InboundSocketMessage round-trip`() {
        val msg =
            InboundSocketMessage(
                id = "msg_1",
                channel = "telegram",
                chatId = "telegram_123",
                content = "Hello",
                ts = "2024-01-01T00:00:00Z",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<InboundSocketMessage>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `OutboundSocketMessage round-trip with meta`() {
        val msg =
            OutboundSocketMessage(
                replyTo = "msg_1",
                channel = "telegram",
                chatId = "telegram_123",
                content = "Response",
                meta = mapOf("key" to "value"),
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<OutboundSocketMessage>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `CommandSocketMessage round-trip with args`() {
        val msg =
            CommandSocketMessage(
                channel = "telegram",
                chatId = "telegram_123",
                command = "model",
                args = "deepseek/deepseek-chat",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<CommandSocketMessage>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `RegisterMessage round-trip`() {
        val msg = RegisterMessage(client = "gateway")
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<RegisterMessage>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `CliRequestMessage round-trip`() {
        val msg = CliRequestMessage(command = "status", params = mapOf("verbose" to "true"))
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<CliRequestMessage>(encoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `CliRequestMessage with empty params round-trip`() {
        val msg = CliRequestMessage(command = "memory_search")
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<CliRequestMessage>(encoded)
        assertEquals(msg, decoded)
        assertEquals(emptyMap(), decoded.params)
    }

    @Test
    fun `ShutdownMessage serializes with correct type discriminator`() {
        val encoded = json.encodeToString<SocketMessage>(ShutdownMessage)
        assertTrue(encoded.contains(""""type":"shutdown""""), "Expected type=shutdown in: $encoded")
    }

    @Test
    fun `ShutdownMessage round-trip`() {
        val encoded = json.encodeToString<SocketMessage>(ShutdownMessage)
        assertIs<ShutdownMessage>(json.decodeFromString<SocketMessage>(encoded))
    }

    @Test
    fun `type field dispatches to correct subclass`() {
        val inboundJson = """{"type":"inbound","id":"1","channel":"tg","chatId":"123","content":"hi","ts":"2024-01-01T00:00:00Z"}"""
        val outboundJson = """{"type":"outbound","channel":"tg","chatId":"123","content":"resp"}"""
        val commandJson = """{"type":"command","channel":"tg","chatId":"123","command":"new"}"""
        val registerJson = """{"type":"register","client":"gateway"}"""

        val shutdownJson = """{"type":"shutdown"}"""

        assertIs<InboundSocketMessage>(json.decodeFromString<SocketMessage>(inboundJson))
        assertIs<OutboundSocketMessage>(json.decodeFromString<SocketMessage>(outboundJson))
        assertIs<CommandSocketMessage>(json.decodeFromString<SocketMessage>(commandJson))
        assertIs<RegisterMessage>(json.decodeFromString<SocketMessage>(registerJson))
        assertIs<ShutdownMessage>(json.decodeFromString<SocketMessage>(shutdownJson))
    }
}
