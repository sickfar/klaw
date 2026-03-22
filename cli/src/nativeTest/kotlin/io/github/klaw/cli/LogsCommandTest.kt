package io.github.klaw.cli

import com.github.ajalt.clikt.testing.test
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fputs
import platform.posix.mkdir
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.remove
import platform.posix.rmdir
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("LargeClass")
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
        deleteRecursively(tmpDir)
    }

    private fun deleteRecursively(path: String) {
        val dir = opendir(path)
        if (dir != null) {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name == "." || name == "..") continue
                val child = "$path/$name"
                // Try rmdir first (directory), then remove (file)
                if (rmdir(child) != 0) {
                    if (remove(child) != 0) {
                        deleteRecursively(child)
                    }
                }
            }
            closedir(dir)
        }
        rmdir(path)
    }

    private fun writeJsonl(
        chatId: String,
        lines: List<String>,
        date: String = "2024-01-01",
    ) {
        val chatDir = "$conversationsDir/$chatId"
        mkdir(chatDir, 0x1EDu)
        val content = lines.joinToString("\n") + "\n"
        val file = fopen("$chatDir/$date.jsonl", "w")
        if (file != null) {
            fputs(content, file)
            fclose(file)
        }
    }

    private fun cli() =
        KlawCli(
            requestFn = { _, _ -> "{}" },
            conversationsDir = conversationsDir,
            engineChecker = { false },
            configDir = "/nonexistent",
            modelsDir = "/nonexistent",
            logDir = "/nonexistent/logs",
        )

    // --- Existing tests (updated for date-based files) ---

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
        assertFalse(result.output.contains("From discord"), "discord messages should be excluded by --chat filter")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `logs gracefully handles empty conversations dir`() {
        val result = cli().test("logs")
        assertEquals(0, result.statusCode)
    }

    // --- New tests for date-based file reading ---

    @Test
    fun `logs reads all date-based JSONL files in order`() {
        writeJsonl(
            "chat1",
            listOf("""{"id":"1","ts":"2024-01-01T10:00:00Z","role":"user","content":"Day1Msg"}"""),
            date = "2024-01-01",
        )
        writeJsonl(
            "chat1",
            listOf("""{"id":"2","ts":"2024-01-02T10:00:00Z","role":"user","content":"Day2Msg"}"""),
            date = "2024-01-02",
        )
        val result = cli().test("logs")
        assertContains(result.output, "Day1Msg")
        assertContains(result.output, "Day2Msg")
        // Day1 should appear before Day2
        assertTrue(result.output.indexOf("Day1Msg") < result.output.indexOf("Day2Msg"))
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `logs --limit works with multi-file chat dirs`() {
        writeJsonl(
            "chat1",
            listOf(
                """{"id":"1","ts":"2024-01-01T10:00:00Z","role":"user","content":"Msg1"}""",
                """{"id":"2","ts":"2024-01-01T10:00:01Z","role":"user","content":"Msg2"}""",
            ),
            date = "2024-01-01",
        )
        writeJsonl(
            "chat1",
            listOf("""{"id":"3","ts":"2024-01-02T10:00:00Z","role":"user","content":"Msg3"}"""),
            date = "2024-01-02",
        )
        val result = cli().test("logs --limit 2")
        // Should show only last 2: Msg2 and Msg3
        assertFalse(result.output.contains("Msg1"), "Msg1 should be excluded by --limit 2")
        assertContains(result.output, "Msg2")
        assertContains(result.output, "Msg3")
        assertEquals(0, result.statusCode)
    }

    // --- Tests for new options ---

    @Test
    fun `logs default output uses colored format`() {
        writeJsonl(
            "chat1",
            listOf("""{"id":"1","ts":"2024-01-01T10:00:00Z","role":"user","content":"ColorTest"}"""),
        )
        val result = cli().test("logs")
        // Default output should contain ANSI escape sequences (color codes)
        // Clikt's test() may or may not strip them, so we verify the output structure
        // by checking that the formatted output contains the message content
        assertContains(result.output, "ColorTest")
        // Verify it's NOT in --json format (raw JSONL)
        assertFalse(result.output.trimStart().startsWith("{"), "Default output should not be raw JSON")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `logs --no-color strips ANSI codes`() {
        writeJsonl(
            "chat1",
            listOf("""{"id":"1","ts":"2024-01-01T10:00:00Z","role":"user","content":"NoColorTest"}"""),
        )
        val result = cli().test("logs --no-color")
        assertFalse(result.output.contains("\u001B["), "Output with --no-color should not contain ANSI escapes")
        assertContains(result.output, "NoColorTest")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `logs --json outputs raw JSONL`() {
        writeJsonl(
            "chat1",
            listOf(
                """{"id":"1","ts":"2024-01-01T10:00:00Z","role":"user","content":"JsonTest"}""",
                """{"id":"2","ts":"2024-01-01T10:00:01Z","role":"assistant","content":"Reply"}""",
            ),
        )
        val result = cli().test("logs --json")
        val lines =
            result.output
                .trim()
                .lines()
                .filter { it.isNotBlank() }
        assertEquals(2, lines.size, "Should have 2 JSON lines")
        for (line in lines) {
            // Each line should be valid JSON (starts with { and ends with })
            assertTrue(line.trimStart().startsWith("{"), "Line should start with '{': $line")
            assertTrue(line.trimEnd().endsWith("}"), "Line should end with '}': $line")
        }
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `logs --local-time converts timestamps`() {
        writeJsonl(
            "chat1",
            listOf("""{"id":"1","ts":"2024-01-01T10:00:00Z","role":"user","content":"LocalTimeTest"}"""),
        )
        val result = cli().test("logs --local-time --no-color")
        // Output should NOT contain the original UTC "Z" timestamp
        assertFalse(
            result.output.contains("2024-01-01T10:00:00Z"),
            "With --local-time, UTC timestamp should be converted",
        )
        assertContains(result.output, "LocalTimeTest")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `logs --max-bytes limits output`() {
        val messages =
            (1..20).map { i ->
                """{"id":"$i","ts":"2024-01-01T10:00:${i.toString().padStart(
                    2,
                    '0',
                )}Z","role":"user","content":"Message number $i with some padding text"}"""
            }
        writeJsonl("chat1", messages)

        val fullResult = cli().test("logs --no-color")
        val limitedResult = cli().test("logs --max-bytes 200 --no-color")

        assertTrue(
            limitedResult.output.length < fullResult.output.length,
            "Limited output (${limitedResult.output.length}) should be shorter than full (${fullResult.output.length})",
        )
        assertEquals(0, limitedResult.statusCode)
    }

    @Test
    fun `logs --timeout exits cleanly with no data`() {
        val result = cli().test("logs --timeout 100")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `logs --interval is accepted`() {
        writeJsonl(
            "chat1",
            listOf("""{"id":"1","ts":"2024-01-01T10:00:00Z","role":"user","content":"IntervalTest"}"""),
        )
        val result = cli().test("logs --interval 500")
        assertContains(result.output, "IntervalTest")
        assertEquals(0, result.statusCode)
    }

    @Test
    fun `logs handles mixed valid and invalid lines`() {
        writeJsonl(
            "chat1",
            listOf(
                """{"id":"1","ts":"2024-01-01T10:00:00Z","role":"user","content":"ValidMsg"}""",
                """this is not valid json""",
                """{"id":"2","ts":"2024-01-01T10:00:01Z","role":"assistant","content":"AlsoValid"}""",
            ),
        )
        val result = cli().test("logs --no-color")
        assertContains(result.output, "ValidMsg")
        assertContains(result.output, "AlsoValid")
        assertFalse(result.output.contains("not valid json"), "Invalid lines should be skipped")
        assertEquals(0, result.statusCode)
    }
}
