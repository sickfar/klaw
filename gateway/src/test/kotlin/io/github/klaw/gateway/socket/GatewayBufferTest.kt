package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.InboundSocketMessage
import io.github.klaw.common.protocol.SocketMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class GatewayBufferTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private fun sampleInbound(id: String = "msg-1") =
        InboundSocketMessage(
            id = id,
            channel = "telegram",
            chatId = "chat-1",
            content = "hello",
            ts = "2024-01-01T00:00:00Z",
        )

    @Test
    fun `buffer appends message and file contains one JSONL line`(
        @TempDir tempDir: Path,
    ) {
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)

        buffer.append(sampleInbound())

        val lines = File(bufferPath).readLines().filter { it.isNotBlank() }
        assertEquals(1, lines.size)
        assertTrue(lines[0].contains("inbound"))
    }

    @Test
    fun `drain returns messages in order and clears file`(
        @TempDir tempDir: Path,
    ) {
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)

        buffer.append(sampleInbound("msg-1"))
        buffer.append(sampleInbound("msg-2"))

        val drained = buffer.drain()

        assertEquals(2, drained.size)
        assertTrue(buffer.isEmpty())
        assertFalse(File(bufferPath).exists())
    }

    @Test
    fun `drain on empty buffer returns empty list`(
        @TempDir tempDir: Path,
    ) {
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)

        val drained = buffer.drain()

        assertTrue(drained.isEmpty())
    }

    @Test
    fun `isEmpty returns true when no messages buffered`(
        @TempDir tempDir: Path,
    ) {
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)

        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `partial write corrupt last line handled gracefully`(
        @TempDir tempDir: Path,
    ) {
        val bufferPath = tempDir.resolve("buffer.jsonl").toString()
        val buffer = GatewayBuffer(bufferPath)

        val validMessage = sampleInbound("msg-valid")
        val validLine = json.encodeToString<SocketMessage>(validMessage)
        File(bufferPath).writeText("$validLine\n{corrupt\n")

        val drained = buffer.drain()

        assertEquals(1, drained.size)
        val msg = drained[0]
        assertTrue(msg is InboundSocketMessage)
        assertEquals("msg-valid", (msg as InboundSocketMessage).id)
    }
}
