package io.github.klaw.gateway.jsonl

import io.github.klaw.gateway.channel.AttachmentInfo
import io.github.klaw.gateway.channel.IncomingMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.LocalDate
import kotlin.time.Instant

class ConversationJsonlWriterTest {
    @TempDir
    lateinit var tempDir: File

    private fun writer() = ConversationJsonlWriter(tempDir.absolutePath)

    private fun sampleInbound(chatId: String = "telegram_123") =
        IncomingMessage(
            id = "msg-1",
            channel = "telegram",
            chatId = chatId,
            content = "hello",
            ts = Instant.parse("2026-02-24T10:00:00Z"),
        )

    @Test
    fun `inbound message written to correct file path`() =
        runBlocking {
            val writer = writer()
            writer.writeInbound(sampleInbound("telegram_123456"))
            val today = LocalDate.now().toString()
            val file = File(tempDir, "default/telegram_123456/$today.jsonl")
            assertTrue(file.exists(), "Expected file at $file")
        }

    @Test
    fun `inbound message written with role=user`() =
        runBlocking {
            val writer = writer()
            writer.writeInbound(sampleInbound())
            val today = LocalDate.now().toString()
            val line = File(tempDir, "default/telegram_123/$today.jsonl").readLines().first()
            val json = Json.parseToJsonElement(line).jsonObject
            assertEquals("user", json["role"]?.jsonPrimitive?.content)
            assertEquals("hello", json["content"]?.jsonPrimitive?.content)
        }

    @Test
    fun `outbound message written with role=assistant`() =
        runBlocking {
            val writer = writer()
            writer.writeOutbound(chatId = "telegram_123", content = "response text", model = "gpt-4")
            val today = LocalDate.now().toString()
            val line = File(tempDir, "default/telegram_123/$today.jsonl").readLines().first()
            val json = Json.parseToJsonElement(line).jsonObject
            assertEquals("assistant", json["role"]?.jsonPrimitive?.content)
            assertEquals("response text", json["content"]?.jsonPrimitive?.content)
        }

    @Test
    fun `file path includes YYYY-MM-DD date`() =
        runBlocking {
            val writer = writer()
            writer.writeInbound(sampleInbound())
            val today = LocalDate.now().toString()
            assertTrue(today.matches(Regex("\\d{4}-\\d{2}-\\d{2}")))
            val file = File(tempDir, "default/telegram_123/$today.jsonl")
            assertTrue(file.exists())
        }

    @Test
    fun `messages appended not overwritten`() =
        runBlocking {
            val writer = writer()
            writer.writeInbound(sampleInbound())
            writer.writeInbound(sampleInbound())
            val today = LocalDate.now().toString()
            val lines = File(tempDir, "default/telegram_123/$today.jsonl").readLines()
            assertEquals(2, lines.size)
        }

    @Test
    fun `concurrent writes don't corrupt file`() =
        runBlocking {
            val writer = writer()
            val jobs =
                (1..20).map { i ->
                    async {
                        writer.writeInbound(sampleInbound().copy(id = "msg-$i", content = "msg $i"))
                    }
                }
            jobs.awaitAll()
            val today = LocalDate.now().toString()
            val lines = File(tempDir, "default/telegram_123/$today.jsonl").readLines()
            assertEquals(20, lines.size)
            lines.forEach { line -> Json.parseToJsonElement(line) }
        }

    @Test
    fun `file created if not exists`() =
        runBlocking {
            val writer = writer()
            writer.writeOutbound(chatId = "telegram_999", content = "hi")
            val today = LocalDate.now().toString()
            assertTrue(File(tempDir, "default/telegram_999/$today.jsonl").exists())
        }

    @Test
    fun `directories created if not exist`() =
        runBlocking {
            val writer = writer()
            val nestedChatId = "telegram_999888777"
            writer.writeInbound(sampleInbound(nestedChatId))
            val today = LocalDate.now().toString()
            assertTrue(File(tempDir, "default/$nestedChatId/$today.jsonl").exists())
        }

    @Test
    fun `writeInbound rejects path traversal chatId`() {
        val writer = writer()
        var thrown = false
        try {
            runBlocking { writer.writeInbound(sampleInbound("../evil")) }
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown, "Expected IllegalArgumentException for chatId '../evil'")
    }

    @Test
    fun `writeOutbound rejects path traversal chatId`() {
        val writer = writer()
        var thrown = false
        try {
            runBlocking { writer.writeOutbound(chatId = "../../etc/passwd", content = "content") }
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown, "Expected IllegalArgumentException for chatId '../../etc/passwd'")
    }

    @Test
    fun `writeInbound rejects chatId with dots only`() {
        val writer = writer()
        var thrown = false
        try {
            runBlocking { writer.writeInbound(sampleInbound("..")) }
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown, "Expected IllegalArgumentException for chatId '..'")
    }

    @Test
    fun `writeInbound rejects chatId with special chars`() {
        val writer = writer()
        var thrown = false
        try {
            runBlocking { writer.writeInbound(sampleInbound("chat<id>")) }
        } catch (_: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown, "Expected IllegalArgumentException for chatId 'chat<id>'")
    }

    @Test
    fun `writeInbound accepts valid telegram chatId`() =
        runBlocking {
            val writer = writer()
            // Should not throw
            writer.writeInbound(sampleInbound("telegram_12345"))
        }

    @Test
    fun `writeInbound accepts valid subagent chatId`() =
        runBlocking {
            val writer = writer()
            // Should not throw
            writer.writeInbound(sampleInbound("subagent:name"))
        }

    @Test
    fun `writeInbound accepts discord channel chatId`() =
        runBlocking {
            val writer = writer()
            // Should not throw
            writer.writeInbound(sampleInbound("discord_channel_456"))
        }

    @Test
    fun `inbound message scoped under agentId directory`() =
        runBlocking {
            val writer = writer()
            val msg = sampleInbound("telegram_123").copy(agentId = "work-agent")
            writer.writeInbound(msg)
            val today = LocalDate.now().toString()
            val file = File(tempDir, "work-agent/telegram_123/$today.jsonl")
            assertTrue(file.exists(), "Expected file at $file")
        }

    @Test
    fun `outbound message scoped under agentId directory`() =
        runBlocking {
            val writer = writer()
            writer.writeOutbound(agentId = "personal", chatId = "telegram_999", content = "hi")
            val today = LocalDate.now().toString()
            val file = File(tempDir, "personal/telegram_999/$today.jsonl")
            assertTrue(file.exists(), "Expected file at $file")
        }

    @Test
    fun `messages from different agents are stored in separate directories`() =
        runBlocking {
            val writer = writer()
            writer.writeInbound(sampleInbound("telegram_1").copy(agentId = "agent-a"))
            writer.writeInbound(sampleInbound("telegram_1").copy(agentId = "agent-b"))
            val today = LocalDate.now().toString()
            assertTrue(File(tempDir, "agent-a/telegram_1/$today.jsonl").exists())
            assertTrue(File(tempDir, "agent-b/telegram_1/$today.jsonl").exists())
        }

    @Test
    fun `inbound message with attachments includes attachments in JSONL`() =
        runBlocking {
            val writer = writer()
            val message =
                sampleInbound().copy(
                    attachments =
                        listOf(
                            AttachmentInfo(
                                path = "/data/photo.jpg",
                                mimeType = "image/jpeg",
                                originalName = "sunset.jpg",
                            ),
                            AttachmentInfo(path = "/data/doc.png", mimeType = "image/png"),
                        ),
                )
            writer.writeInbound(message)
            val today =
                java.time.LocalDate
                    .now()
                    .toString()
            val line = File(tempDir, "default/telegram_123/$today.jsonl").readLines().first()
            val json = Json.parseToJsonElement(line).jsonObject
            assertTrue(json.containsKey("attachments"), "JSONL should contain attachments key")
            val attachments = json["attachments"]!!.jsonArray
            assertEquals(2, attachments.size)

            val first = attachments[0].jsonObject
            assertEquals("/data/photo.jpg", first["path"]?.jsonPrimitive?.content)
            assertEquals("image/jpeg", first["mimeType"]?.jsonPrimitive?.content)
            assertEquals("sunset.jpg", first["originalName"]?.jsonPrimitive?.content)

            val second = attachments[1].jsonObject
            assertEquals("/data/doc.png", second["path"]?.jsonPrimitive?.content)
            assertEquals("image/png", second["mimeType"]?.jsonPrimitive?.content)
            assertFalse(second.containsKey("originalName"), "Null originalName should be omitted")
        }

    @Test
    fun `inbound message without attachments has no attachments field`() =
        runBlocking {
            val writer = writer()
            writer.writeInbound(sampleInbound())
            val today =
                java.time.LocalDate
                    .now()
                    .toString()
            val line = File(tempDir, "default/telegram_123/$today.jsonl").readLines().first()
            val json = Json.parseToJsonElement(line).jsonObject
            assertFalse(json.containsKey("attachments"), "JSONL should NOT contain attachments when empty")
        }
}
