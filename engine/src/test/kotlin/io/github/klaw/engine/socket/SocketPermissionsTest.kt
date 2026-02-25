package io.github.klaw.engine.socket

import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
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

    @BeforeEach
    fun setUp() {
        socketPath = tempDir.resolve("engine.sock").toString()
        val handler = mockk<SocketMessageHandler>(relaxed = true)
        server = EngineSocketServer(socketPath, handler)
        server.start()
        Thread.sleep(100)
    }

    @AfterEach
    fun tearDown() {
        server.stop()
        Thread.sleep(50)
    }

    @Test
    fun `socket file permissions are 600 after server start`() {
        val perms = Files.getPosixFilePermissions(Path.of(socketPath))
        assertEquals(PosixFilePermissions.fromString("rw-------"), perms)
    }
}
