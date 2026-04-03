package io.github.klaw.common.protocol

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
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
    fun `ApprovalRequestMessage serializes with correct type discriminator`() {
        val msg =
            ApprovalRequestMessage(
                id = "apr_001",
                chatId = "telegram_123456",
                command = "apt upgrade -y",
                riskScore = 8,
                timeout = 300,
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        assertTrue(encoded.contains(""""type":"approval_request""""), "Expected type=approval_request in: $encoded")
    }

    @Test
    fun `ApprovalRequestMessage round-trip`() {
        val msg =
            ApprovalRequestMessage(
                id = "apr_001",
                chatId = "telegram_123456",
                command = "apt upgrade -y",
                riskScore = 8,
                timeout = 300,
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<ApprovalRequestMessage>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `ApprovalResponseMessage serializes with correct type discriminator`() {
        val msg = ApprovalResponseMessage(id = "apr_001", approved = true)
        val encoded = json.encodeToString<SocketMessage>(msg)
        assertTrue(encoded.contains(""""type":"approval_response""""), "Expected type=approval_response in: $encoded")
    }

    @Test
    fun `ApprovalResponseMessage round-trip approved`() {
        val msg = ApprovalResponseMessage(id = "apr_001", approved = true)
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<ApprovalResponseMessage>(decoded)
        assertEquals(msg, decoded)
    }

    @Test
    fun `ApprovalResponseMessage round-trip denied`() {
        val msg = ApprovalResponseMessage(id = "apr_001", approved = false)
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<ApprovalResponseMessage>(decoded)
        assertEquals(false, decoded.approved)
    }

    @Test
    fun `PingMessage serializes with correct type discriminator`() {
        val encoded = json.encodeToString<SocketMessage>(PingMessage)
        assertTrue(encoded.contains(""""type":"ping""""), "Expected type=ping in: $encoded")
    }

    @Test
    fun `PingMessage round-trip`() {
        val encoded = json.encodeToString<SocketMessage>(PingMessage)
        assertIs<PingMessage>(json.decodeFromString<SocketMessage>(encoded))
    }

    @Test
    fun `PongMessage serializes with correct type discriminator`() {
        val encoded = json.encodeToString<SocketMessage>(PongMessage)
        assertTrue(encoded.contains(""""type":"pong""""), "Expected type=pong in: $encoded")
    }

    @Test
    fun `PongMessage round-trip`() {
        val encoded = json.encodeToString<SocketMessage>(PongMessage)
        assertIs<PongMessage>(json.decodeFromString<SocketMessage>(encoded))
    }

    @Test
    fun `RestartRequestSocketMessage serializes with correct type discriminator`() {
        val encoded = json.encodeToString<SocketMessage>(RestartRequestSocketMessage)
        assertTrue(encoded.contains(""""type":"restart_request""""), "Expected type=restart_request in: $encoded")
    }

    @Test
    fun `RestartRequestSocketMessage round-trip`() {
        val encoded = json.encodeToString<SocketMessage>(RestartRequestSocketMessage)
        assertIs<RestartRequestSocketMessage>(json.decodeFromString<SocketMessage>(encoded))
    }

    @Test
    fun `type field dispatches to correct subclass`() {
        val inboundJson =
            """{"type":"inbound","id":"1","channel":"tg","chatId":"123","content":"hi","ts":"2024-01-01T00:00:00Z"}"""
        val outboundJson = """{"type":"outbound","channel":"tg","chatId":"123","content":"resp"}"""
        val commandJson = """{"type":"command","channel":"tg","chatId":"123","command":"new"}"""
        val registerJson = """{"type":"register","client":"gateway"}"""

        val shutdownJson = """{"type":"shutdown"}"""
        val approvalReqJson =
            """{"type":"approval_request","id":"apr_1","chatId":"123","command":"ls","riskScore":2,"timeout":60}"""
        val approvalRespJson = """{"type":"approval_response","id":"apr_1","approved":true}"""
        val pingJson = """{"type":"ping"}"""
        val pongJson = """{"type":"pong"}"""
        val restartReqJson = """{"type":"restart_request"}"""

        assertIs<InboundSocketMessage>(json.decodeFromString<SocketMessage>(inboundJson))
        assertIs<OutboundSocketMessage>(json.decodeFromString<SocketMessage>(outboundJson))
        assertIs<CommandSocketMessage>(json.decodeFromString<SocketMessage>(commandJson))
        assertIs<RegisterMessage>(json.decodeFromString<SocketMessage>(registerJson))
        assertIs<ShutdownMessage>(json.decodeFromString<SocketMessage>(shutdownJson))
        assertIs<ApprovalRequestMessage>(json.decodeFromString<SocketMessage>(approvalReqJson))
        assertIs<ApprovalResponseMessage>(json.decodeFromString<SocketMessage>(approvalRespJson))
        assertIs<PingMessage>(json.decodeFromString<SocketMessage>(pingJson))
        assertIs<PongMessage>(json.decodeFromString<SocketMessage>(pongJson))
        assertIs<RestartRequestSocketMessage>(json.decodeFromString<SocketMessage>(restartReqJson))
    }

    @Test
    fun `InboundSocketMessage with sender fields round-trip`() {
        val msg =
            InboundSocketMessage(
                id = "msg_1",
                channel = "telegram",
                chatId = "telegram_123",
                content = "Hello",
                ts = "2024-01-01T00:00:00Z",
                senderId = "user_123",
                senderName = "John Doe",
                chatType = "group",
                chatTitle = "Dev Chat",
                messageId = "msg_42",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<InboundSocketMessage>(decoded)
        assertEquals("user_123", decoded.senderId)
        assertEquals("John Doe", decoded.senderName)
        assertEquals("group", decoded.chatType)
        assertEquals("Dev Chat", decoded.chatTitle)
        assertEquals("msg_42", decoded.messageId)
    }

    @Test
    fun `InboundSocketMessage without sender fields deserializes with nulls`() {
        val raw =
            """{"type":"inbound","id":"1","channel":"tg","chatId":"123","content":"hi","ts":"2024-01-01T00:00:00Z"}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<InboundSocketMessage>(decoded)
        assertNull(decoded.senderId)
        assertNull(decoded.senderName)
        assertNull(decoded.chatType)
        assertNull(decoded.chatTitle)
        assertNull(decoded.messageId)
    }

    @Test
    fun `CommandSocketMessage with sender fields round-trip`() {
        val msg =
            CommandSocketMessage(
                channel = "telegram",
                chatId = "telegram_456",
                command = "new",
                senderId = "user_456",
                senderName = "Jane",
                chatType = "private",
                chatTitle = null,
                messageId = "msg_99",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<CommandSocketMessage>(decoded)
        assertEquals("user_456", decoded.senderId)
        assertEquals("Jane", decoded.senderName)
        assertEquals("private", decoded.chatType)
        assertNull(decoded.chatTitle)
        assertEquals("msg_99", decoded.messageId)
    }

    @Test
    fun `CommandSocketMessage without sender fields deserializes with nulls`() {
        val raw = """{"type":"command","channel":"tg","chatId":"123","command":"new"}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<CommandSocketMessage>(decoded)
        assertNull(decoded.senderId)
        assertNull(decoded.senderName)
        assertNull(decoded.chatType)
        assertNull(decoded.chatTitle)
        assertNull(decoded.messageId)
    }

    // --- agentId tests ---

    @Test
    fun `InboundSocketMessage with explicit agentId round-trip`() {
        val msg =
            InboundSocketMessage(
                id = "msg_1",
                channel = "telegram",
                chatId = "telegram_123",
                content = "Hello",
                ts = "2024-01-01T00:00:00Z",
                agentId = "agent_work",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<InboundSocketMessage>(decoded)
        assertEquals("agent_work", decoded.agentId)
    }

    @Test
    fun `InboundSocketMessage defaults agentId to default`() {
        val raw =
            """{"type":"inbound","id":"1","channel":"tg","chatId":"123","content":"hi","ts":"2024-01-01T00:00:00Z"}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<InboundSocketMessage>(decoded)
        assertEquals("default", decoded.agentId)
    }

    @Test
    fun `OutboundSocketMessage with explicit agentId round-trip`() {
        val msg =
            OutboundSocketMessage(
                channel = "telegram",
                chatId = "telegram_123",
                content = "Response",
                agentId = "agent_work",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<OutboundSocketMessage>(decoded)
        assertEquals("agent_work", decoded.agentId)
    }

    @Test
    fun `OutboundSocketMessage defaults agentId to default`() {
        val raw = """{"type":"outbound","channel":"tg","chatId":"123","content":"resp"}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<OutboundSocketMessage>(decoded)
        assertEquals("default", decoded.agentId)
    }

    @Test
    fun `CommandSocketMessage with explicit agentId round-trip`() {
        val msg =
            CommandSocketMessage(
                channel = "telegram",
                chatId = "telegram_123",
                command = "status",
                agentId = "agent_home",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<CommandSocketMessage>(decoded)
        assertEquals("agent_home", decoded.agentId)
    }

    @Test
    fun `CommandSocketMessage defaults agentId to default`() {
        val raw = """{"type":"command","channel":"tg","chatId":"123","command":"new"}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<CommandSocketMessage>(decoded)
        assertEquals("default", decoded.agentId)
    }

    @Test
    fun `ApprovalRequestMessage with explicit agentId round-trip`() {
        val msg =
            ApprovalRequestMessage(
                id = "apr_001",
                chatId = "telegram_123456",
                command = "apt upgrade -y",
                riskScore = 8,
                timeout = 300,
                agentId = "agent_pi",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<ApprovalRequestMessage>(decoded)
        assertEquals("agent_pi", decoded.agentId)
    }

    @Test
    fun `ApprovalRequestMessage defaults agentId to default`() {
        val raw =
            """{"type":"approval_request","id":"apr_1","chatId":"123","command":"ls","riskScore":2,"timeout":60}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<ApprovalRequestMessage>(decoded)
        assertEquals("default", decoded.agentId)
    }

    @Test
    fun `ApprovalResponseMessage with explicit agentId round-trip`() {
        val msg = ApprovalResponseMessage(id = "apr_001", approved = true, agentId = "agent_pi")
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<ApprovalResponseMessage>(decoded)
        assertEquals("agent_pi", decoded.agentId)
    }

    @Test
    fun `ApprovalResponseMessage defaults agentId to default`() {
        val raw = """{"type":"approval_response","id":"apr_1","approved":true}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<ApprovalResponseMessage>(decoded)
        assertEquals("default", decoded.agentId)
    }

    @Test
    fun `ApprovalDismissMessage with explicit agentId round-trip`() {
        val msg = ApprovalDismissMessage(id = "apr_001", agentId = "agent_pi")
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<ApprovalDismissMessage>(decoded)
        assertEquals("agent_pi", decoded.agentId)
    }

    @Test
    fun `ApprovalDismissMessage defaults agentId to default`() {
        val raw = """{"type":"approval_dismiss","id":"apr_1"}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<ApprovalDismissMessage>(decoded)
        assertEquals("default", decoded.agentId)
    }

    @Test
    fun `StreamDeltaSocketMessage with explicit agentId round-trip`() {
        val msg =
            StreamDeltaSocketMessage(
                channel = "telegram",
                chatId = "telegram_123",
                delta = "Hello",
                streamId = "stream_1",
                agentId = "agent_work",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<StreamDeltaSocketMessage>(decoded)
        assertEquals("agent_work", decoded.agentId)
    }

    @Test
    fun `StreamDeltaSocketMessage defaults agentId to default`() {
        val raw =
            """{"type":"stream_delta","channel":"tg","chatId":"123","delta":"hi","streamId":"s1"}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<StreamDeltaSocketMessage>(decoded)
        assertEquals("default", decoded.agentId)
    }

    @Test
    fun `StreamEndSocketMessage with explicit agentId round-trip`() {
        val msg =
            StreamEndSocketMessage(
                channel = "telegram",
                chatId = "telegram_123",
                streamId = "stream_1",
                fullContent = "Hello world",
                agentId = "agent_work",
            )
        val encoded = json.encodeToString<SocketMessage>(msg)
        val decoded = json.decodeFromString<SocketMessage>(encoded)
        assertIs<StreamEndSocketMessage>(decoded)
        assertEquals("agent_work", decoded.agentId)
    }

    @Test
    fun `StreamEndSocketMessage defaults agentId to default`() {
        val raw =
            """{"type":"stream_end","channel":"tg","chatId":"123","streamId":"s1","fullContent":"hi"}"""
        val decoded = json.decodeFromString<SocketMessage>(raw)
        assertIs<StreamEndSocketMessage>(decoded)
        assertEquals("default", decoded.agentId)
    }

    @Test
    fun `CliRequestMessage with explicit agentId round-trip`() {
        val msg = CliRequestMessage(command = "status", agentId = "agent_home")
        val encoded = json.encodeToString(msg)
        val decoded = json.decodeFromString<CliRequestMessage>(encoded)
        assertEquals("agent_home", decoded.agentId)
    }

    @Test
    fun `CliRequestMessage defaults agentId to default`() {
        val msg = CliRequestMessage(command = "status")
        assertEquals("default", msg.agentId)
    }

    @Test
    fun `CliRequestMessage without agentId in JSON deserializes with default`() {
        val raw = """{"command":"status"}"""
        val decoded = json.decodeFromString<CliRequestMessage>(raw)
        assertEquals("default", decoded.agentId)
    }
}
