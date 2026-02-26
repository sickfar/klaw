package io.github.klaw.gateway.jsonl

import io.github.klaw.gateway.channel.IncomingMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
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
            val file = File(tempDir, "telegram_123456/$today.jsonl")
            assertTrue(file.exists(), "Expected file at $file")
        }

    @Test
    fun `inbound message written with role=user`() =
        runBlocking {
            val writer = writer()
            writer.writeInbound(sampleInbound())
            val today = LocalDate.now().toString()
            val line = File(tempDir, "telegram_123/$today.jsonl").readLines().first()
            val json = Json.parseToJsonElement(line).jsonObject
            assertEquals("user", json["role"]?.jsonPrimitive?.content)
            assertEquals("hello", json["content"]?.jsonPrimitive?.content)
        }

    @Test
    fun `outbound message written with role=assistant`() =
        runBlocking {
            val writer = writer()
            writer.writeOutbound("telegram_123", "response text", model = "gpt-4")
            val today = LocalDate.now().toString()
            val line = File(tempDir, "telegram_123/$today.jsonl").readLines().first()
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
            val file = File(tempDir, "telegram_123/$today.jsonl")
            assertTrue(file.exists())
        }

    @Test
    fun `messages appended not overwritten`() =
        runBlocking {
            val writer = writer()
            writer.writeInbound(sampleInbound())
            writer.writeInbound(sampleInbound())
            val today = LocalDate.now().toString()
            val lines = File(tempDir, "telegram_123/$today.jsonl").readLines()
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
            val lines = File(tempDir, "telegram_123/$today.jsonl").readLines()
            assertEquals(20, lines.size)
            lines.forEach { line -> Json.parseToJsonElement(line) }
        }

    @Test
    fun `file created if not exists`() =
        runBlocking {
            val writer = writer()
            writer.writeOutbound("telegram_999", "hi")
            val today = LocalDate.now().toString()
            assertTrue(File(tempDir, "telegram_999/$today.jsonl").exists())
        }

    @Test
    fun `directories created if not exist`() =
        runBlocking {
            val writer = writer()
            val nestedChatId = "telegram_999888777"
            writer.writeInbound(sampleInbound(nestedChatId))
            val today = LocalDate.now().toString()
            assertTrue(File(tempDir, "$nestedChatId/$today.jsonl").exists())
        }
}
