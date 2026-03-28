package io.github.klaw.engine.socket

import io.github.klaw.common.protocol.OutboundSocketMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class EngineOutboundBufferTest {
    @TempDir
    lateinit var tempDir: Path

    private fun bufferPath(): String = tempDir.resolve("engine-outbound-buffer.jsonl").toString()

    private fun outbound(
        content: String,
        chatId: String = "chat-1",
    ): OutboundSocketMessage =
        OutboundSocketMessage(
            channel = "telegram",
            chatId = chatId,
            content = content,
        )

    @Test
    fun `append and drain round-trip`() {
        val buffer = EngineOutboundBuffer(bufferPath())
        val msg = outbound("hello")
        buffer.append(msg)

        val drained = buffer.drain()
        assertEquals(1, drained.size)
        val received = drained[0] as OutboundSocketMessage
        assertEquals("hello", received.content)
        assertEquals("chat-1", received.chatId)
    }

    @Test
    fun `drain returns empty list when no file exists`() {
        val buffer = EngineOutboundBuffer(bufferPath())
        val drained = buffer.drain()
        assertTrue(drained.isEmpty())
    }

    @Test
    fun `isEmpty returns true when no messages buffered`() {
        val buffer = EngineOutboundBuffer(bufferPath())
        assertTrue(buffer.isEmpty())
    }

    @Test
    fun `insertion order preserved across multiple appends`() {
        val buffer = EngineOutboundBuffer(bufferPath())
        buffer.append(outbound("first"))
        buffer.append(outbound("second"))
        buffer.append(outbound("third"))

        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals("first", (drained[0] as OutboundSocketMessage).content)
        assertEquals("second", (drained[1] as OutboundSocketMessage).content)
        assertEquals("third", (drained[2] as OutboundSocketMessage).content)
    }

    @Test
    fun `malformed lines skipped gracefully during drain`() {
        val path = bufferPath()
        val buffer = EngineOutboundBuffer(path)
        buffer.append(outbound("good-before"))

        // Inject a malformed line directly into the file
        File(path).appendText("this is not valid json\n")

        buffer.append(outbound("good-after"))

        val drained = buffer.drain()
        assertEquals(2, drained.size)
        assertEquals("good-before", (drained[0] as OutboundSocketMessage).content)
        assertEquals("good-after", (drained[1] as OutboundSocketMessage).content)
    }

    @Test
    fun `max lines cap enforced - oldest messages dropped`() {
        val path = bufferPath()
        val buffer = EngineOutboundBuffer(path, maxLines = 3)

        buffer.append(outbound("msg-1"))
        buffer.append(outbound("msg-2"))
        buffer.append(outbound("msg-3"))
        // This fourth append should trigger truncation, dropping msg-1
        buffer.append(outbound("msg-4"))

        val drained = buffer.drain()
        assertEquals(3, drained.size)
        assertEquals("msg-2", (drained[0] as OutboundSocketMessage).content)
        assertEquals("msg-3", (drained[1] as OutboundSocketMessage).content)
        assertEquals("msg-4", (drained[2] as OutboundSocketMessage).content)
    }

    @Test
    @Suppress("TooGenericExceptionCaught")
    fun `file has owner-only permissions after first append`() {
        val path = bufferPath()
        val buffer = EngineOutboundBuffer(path)
        buffer.append(outbound("secret"))

        val file = File(path)
        assertTrue(file.exists())
        try {
            val perms =
                java.nio.file.Files
                    .getPosixFilePermissions(file.toPath())
            val expected = PosixFilePermissions.fromString("rw-------")
            assertEquals(expected, perms)
        } catch (_: UnsupportedOperationException) {
            // On non-POSIX filesystems (Windows), skip permission check
        }
    }

    @Test
    fun `drain clears the buffer file`() {
        val path = bufferPath()
        val buffer = EngineOutboundBuffer(path)
        buffer.append(outbound("message"))

        assertTrue(!buffer.isEmpty())

        buffer.drain()

        assertTrue(buffer.isEmpty())
        // File should no longer exist after drain
        assertTrue(!File(path).exists())
    }
}
