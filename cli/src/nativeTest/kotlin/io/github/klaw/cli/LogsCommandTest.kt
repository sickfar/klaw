package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.mkdir
import platform.posix.remove
import platform.posix.rmdir
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

@OptIn(ExperimentalForeignApi::class)
class LogsCommandTest {
    private val tmpDir = "/tmp/klaw-logs-test-${platform.posix.getpid()}"
    private val conversationsDir = "$tmpDir/conversations"

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1EDu)
        mkdir(conversationsDir, 0x1EDu)
    }

    @AfterTest
    fun teardown() {
        deleteDir(tmpDir)
    }

    private fun deleteDir(path: String) {
        // Best-effort cleanup: try to remove files in known locations
        listOf(
            "$conversationsDir/telegram_123/telegram_123.jsonl",
            "$conversationsDir/telegram_123",
        ).forEach { tryRemove(it) }
        tryRemove(conversationsDir)
        tryRemove(path)
    }

    private fun tryRemove(path: String) {
        remove(path)
        rmdir(path)
    }

    private fun writeJsonl(
        chatId: String,
        lines: List<String>,
    ) {
        val chatDir = "$conversationsDir/$chatId"
        mkdir(chatDir, 0x1EDu)
        val content = lines.joinToString("\n") + "\n"
        val file = platform.posix.fopen("$chatDir/$chatId.jsonl", "w")
        if (file != null) {
            platform.posix.fputs(content, file)
            platform.posix.fclose(file)
        }
    }

    private fun cli() =
        KlawCli(
            requestFn = { _, _ -> "{}" },
            conversationsDir = conversationsDir,
            engineSocketPath = "/nonexistent",
            configDir = "/nonexistent",
            modelsDir = "/nonexistent",
        )

    @Test
    fun `logs shows recent messages from JSONL`() {
        writeJsonl(
            "telegram_123",
            listOf(
                """{"id":"1","ts":"2024-01-01T10:00:00Z","role":"user","content":"Hello"}""",
                """{"id":"2","ts":"2024-01-01T10:00:01Z","role":"assistant","content":"Hi there"}""",
            ),
        )
        val result = cli().test("logs")
        assertContains(result.output, "Hello")
        assertContains(result.output, "Hi there")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `logs --chat filters by chat_id`() {
        writeJsonl(
            "telegram_123",
            listOf("""{"id":"1","ts":"2024-01-01T10:00:00Z","role":"user","content":"From telegram"}"""),
        )
        writeJsonl(
            "discord_456",
            listOf("""{"id":"2","ts":"2024-01-01T10:00:01Z","role":"user","content":"From discord"}"""),
        )
        val result = cli().test("logs --chat telegram_123")
        assertContains(result.output, "From telegram")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `logs gracefully handles empty conversations dir`() {
        val result = cli().test("logs")
        assertEquals(0, result.statusCode)
    }
}
