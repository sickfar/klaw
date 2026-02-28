package io.github.klaw.engine.socket

import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

class SocketPermissionsTest {
    @TempDir
    lateinit var tempDir: Path

    private lateinit var socketPath: String
    private lateinit var server: EngineSocketServer

    @AfterEach
    fun tearDown() {
        if (::server.isInitialized) {
            server.stop()
            Thread.sleep(50)
        }
    }

    @Test
    fun `socket file permissions default to 600`() {
        socketPath = tempDir.resolve("engine.sock").toString()
        val handler = mockk<SocketMessageHandler>(relaxed = true)
        server = EngineSocketServer(socketPath, handler)
        server.start()
        Thread.sleep(100)
        val perms = Files.getPosixFilePermissions(Path.of(socketPath))
        assertEquals(PosixFilePermissions.fromString("rw-------"), perms)
    }

    @Test
    fun `socket file permissions configurable via constructor param`() {
        socketPath = tempDir.resolve("engine2.sock").toString()
        val handler = mockk<SocketMessageHandler>(relaxed = true)
        server = EngineSocketServer(socketPath, handler, socketPerms = "rw-rw-rw-")
        server.start()
        Thread.sleep(100)
        val perms = Files.getPosixFilePermissions(Path.of(socketPath))
        assertEquals(PosixFilePermissions.fromString("rw-rw-rw-"), perms)
    }
}
