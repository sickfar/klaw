package io.github.klaw.gateway.pairing

import io.github.klaw.common.config.PairingRequest
import io.github.klaw.common.paths.KlawPathsSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.time.Instant
import java.time.temporal.ChronoUnit

class PairingServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private fun testPaths(): KlawPathsSnapshot {
        val dir = tempDir.toString()
        return KlawPathsSnapshot(
            config = dir,
            data = dir,
            state = dir,
            cache = dir,
            workspace = dir,
            enginePort = 7470,
            engineHost = "127.0.0.1",
            gatewayBuffer = "$dir/buffer.jsonl",
            engineOutboundBuffer = "$dir/engine-outbound-buffer.jsonl",
            klawDb = "$dir/klaw.db",
            schedulerDb = "$dir/scheduler.db",
            conversations = "$dir/conversations",
            summaries = "$dir/summaries",
            memory = "$dir/memory",
            skills = "$dir/skills",
            models = "$dir/models",
            logs = "$dir/logs",
            deployConf = "$dir/deploy",
            hybridDockerCompose = "$dir/docker-compose.json",
            pairingRequests = "$dir/pairing_requests.json",
        )
    }

    @Test
    fun `generated code is 6 chars alphanumeric uppercase`() {
        val service = PairingService(testPaths())
        val result = service.generateCode("telegram", "chat1", "user1")
        assertTrue(result is PairingCodeResult.Success)
        val code = (result as PairingCodeResult.Success).code
        assertEquals(6, code.length)
        assertTrue(code.all { it in 'A'..'Z' || it in '0'..'9' })
    }

    @Test
    fun `rate limit returns RateLimited for same chatId within 1 minute`() {
        val service = PairingService(testPaths())
        val first = service.generateCode("telegram", "chat1", "user1")
        assertTrue(first is PairingCodeResult.Success)

        val second = service.generateCode("telegram", "chat1", "user1")
        assertTrue(second is PairingCodeResult.RateLimited)
    }

    @Test
    fun `rate limit allows different chatIds`() {
        val service = PairingService(testPaths())
        val first = service.generateCode("telegram", "chat1", "user1")
        assertTrue(first is PairingCodeResult.Success)

        val second = service.generateCode("telegram", "chat2", "user1")
        assertTrue(second is PairingCodeResult.Success)
    }

    @Test
    fun `cleanExpired removes requests older than 5 minutes`() {
        val paths = testPaths()
        val service = PairingService(paths)

        // Manually write an expired request
        val expired =
            PairingRequest(
                code = "ABC123",
                channel = "telegram",
                chatId = "chat1",
                userId = "user1",
                createdAt = Instant.now().minus(10, ChronoUnit.MINUTES).toString(),
            )
        service.saveRequests(listOf(expired))

        // Create a new service that loads from file, then clean
        val service2 = PairingService(paths)
        service2.cleanExpired()
        assertFalse(service2.hasPendingRequests())
    }

    @Test
    fun `cleanExpired keeps non-expired requests`() {
        val paths = testPaths()
        val service = PairingService(paths)

        val fresh =
            PairingRequest(
                code = "ABC123",
                channel = "telegram",
                chatId = "chat1",
                userId = "user1",
                createdAt = Instant.now().toString(),
            )
        service.saveRequests(listOf(fresh))

        val service2 = PairingService(paths)
        service2.cleanExpired()
        assertTrue(service2.hasPendingRequests())
    }

    @Test
    fun `duplicate replacement removes previous request for same chatId and userId`() {
        val paths = testPaths()
        val service = PairingService(paths)
        service.generateCode("telegram", "chat1", "user1") as PairingCodeResult.Success

        // Force clear rate limit by using a new service instance with the file
        val service2 = PairingService(paths)
        val second = service2.generateCode("telegram", "chat1", "user1") as PairingCodeResult.Success

        // Should have replaced, so only 1 request in file
        val loaded = service2.loadRequests()
        assertEquals(1, loaded.size)
        assertEquals(second.code, loaded[0].code)
    }

    @Test
    fun `file round trip preserves data`() {
        val paths = testPaths()
        val service = PairingService(paths)
        val requests =
            listOf(
                PairingRequest("ABC123", "telegram", "chat1", "user1", Instant.now().toString()),
                PairingRequest("DEF456", "discord", "chat2", null, Instant.now().toString()),
            )
        service.saveRequests(requests)

        val loaded = service.loadRequests()
        assertEquals(requests, loaded)
    }

    @Test
    fun `hasPendingRequests returns true when requests exist`() {
        val service = PairingService(testPaths())
        service.generateCode("telegram", "chat1", "user1")
        assertTrue(service.hasPendingRequests())
    }

    @Test
    fun `hasPendingRequests returns false when empty`() {
        val service = PairingService(testPaths())
        assertFalse(service.hasPendingRequests())
    }

    @Test
    fun `hasPendingRequests returns false after all expired cleaned`() {
        val paths = testPaths()
        val service = PairingService(paths)

        val expired =
            PairingRequest(
                code = "ABC123",
                channel = "telegram",
                chatId = "chat1",
                userId = "user1",
                createdAt = Instant.now().minus(10, ChronoUnit.MINUTES).toString(),
            )
        service.saveRequests(listOf(expired))

        val service2 = PairingService(paths)
        service2.cleanExpired()
        assertFalse(service2.hasPendingRequests())
    }

    @Test
    fun `loadRequests returns empty list when file does not exist`() {
        val service = PairingService(testPaths())
        val loaded = service.loadRequests()
        assertTrue(loaded.isEmpty())
    }

    @Test
    fun `loadRequests returns empty list for corrupted file`() {
        val paths = testPaths()
        File(paths.pairingRequests).writeText("not json")
        val service = PairingService(paths)
        val loaded = service.loadRequests()
        assertTrue(loaded.isEmpty())
    }
}
