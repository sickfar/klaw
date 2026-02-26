package io.github.klaw.engine.context

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class FileCoreMemoryServiceTest {
    @TempDir
    lateinit var tempDir: Path

    private fun service(): FileCoreMemoryService = FileCoreMemoryService(tempDir.resolve("memory/core_memory.json"))

    @Test
    fun `load when file missing returns empty string`() =
        runTest {
            val result = service().load()
            assertEquals("", result)
        }

    @Test
    fun `getJson when file missing returns empty sections`() =
        runTest {
            val result = service().getJson()
            assertTrue(result.contains("\"user\""))
            assertTrue(result.contains("\"agent\""))
        }

    @Test
    fun `update creates file and adds key to user section`() =
        runTest {
            val svc = service()
            val result = svc.update("user", "name", "Alice")
            assertTrue(result.startsWith("OK"))
            assertTrue(svc.getJson().contains("Alice"))
        }

    @Test
    fun `update adds key to agent section`() =
        runTest {
            val svc = service()
            svc.update("agent", "mood", "happy")
            assertTrue(svc.getJson().contains("happy"))
        }

    @Test
    fun `update overwrites existing key`() =
        runTest {
            val svc = service()
            svc.update("user", "name", "Alice")
            svc.update("user", "name", "Bob")
            val json = svc.getJson()
            assertTrue(json.contains("Bob"))
            assertTrue(!json.contains("Alice"))
        }

    @Test
    fun `delete removes existing key`() =
        runTest {
            val svc = service()
            svc.update("user", "name", "Alice")
            val result = svc.delete("user", "name")
            assertTrue(result.startsWith("OK"))
            assertTrue(!svc.getJson().contains("Alice"))
        }

    @Test
    fun `delete returns error for non-existent key`() =
        runTest {
            val svc = service()
            val result = svc.delete("user", "missing")
            assertTrue(result.startsWith("Error"))
        }

    @Test
    fun `load returns formatted core memory content`() =
        runTest {
            val svc = service()
            svc.update("user", "name", "Alice")
            svc.update("agent", "mood", "happy")
            val result = svc.load()
            assertTrue(result.contains("[user]"))
            assertTrue(result.contains("name: Alice"))
            assertTrue(result.contains("[agent]"))
            assertTrue(result.contains("mood: happy"))
        }

    @Test
    fun `update rejects invalid section`() =
        runTest {
            val result = service().update("invalid", "key", "val")
            assertTrue(result.startsWith("Error"))
        }

    @Test
    fun `delete rejects invalid section`() =
        runTest {
            val result = service().delete("invalid", "key")
            assertTrue(result.startsWith("Error"))
        }
}
