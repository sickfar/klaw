package io.github.klaw.engine.context

import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.ToolCall
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SubagentHistoryLoaderTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun encodeLine(msg: LlmMessage): String = json.encodeToString(msg)

    private fun createSchedulerDir(
        conversationsDir: Path,
        taskName: String,
    ): Path {
        val dir = conversationsDir.resolve("scheduler_$taskName")
        dir.createDirectories()
        return dir
    }

    private fun buildLoader(dir: Path): SubagentHistoryLoader = SubagentHistoryLoader(dir.toString())

    @Test
    fun `missing scheduler directory returns empty list`(
        @TempDir tempDir: Path,
    ) = runTest {
        val loader = buildLoader(tempDir.resolve("nonexistent"))
        val result = loader.loadHistory("task-1", 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `no completed runs in file returns empty list`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dir = createSchedulerDir(tempDir, "task-2")
        // Only a user message, no closing assistant
        dir.resolve("run.jsonl").writeText(encodeLine(LlmMessage(role = "user", content = "Hello")) + "\n")

        val loader = buildLoader(tempDir)
        val result = loader.loadHistory("task-2", 5)
        assertTrue(result.isEmpty())
    }

    @Test
    fun `single complete run returned correctly`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dir = createSchedulerDir(tempDir, "task-3")
        dir.resolve("run.jsonl").writeText(
            encodeLine(LlmMessage(role = "user", content = "What is 2+2?")) + "\n" +
                encodeLine(LlmMessage(role = "assistant", content = "4")) + "\n",
        )

        val loader = buildLoader(tempDir)
        val result = loader.loadHistory("task-3", 5)
        assertEquals(2, result.size)
        assertEquals("user", result[0].role)
        assertEquals("What is 2+2?", result[0].content)
        assertEquals("assistant", result[1].role)
        assertEquals("4", result[1].content)
    }

    @Test
    fun `loads last N complete runs, oldest run first in output`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dir = createSchedulerDir(tempDir, "task-4")
        val sb = StringBuilder()
        for (i in 1..5) {
            sb.append(encodeLine(LlmMessage(role = "user", content = "Run $i user"))).append('\n')
            sb.append(encodeLine(LlmMessage(role = "assistant", content = "Run $i assistant"))).append('\n')
        }
        dir.resolve("runs.jsonl").writeText(sb.toString())

        val loader = buildLoader(tempDir)
        val result = loader.loadHistory("task-4", 3)
        // Should return runs 3, 4, 5 (last 3), each with 2 messages = 6 messages total
        assertEquals(6, result.size)
        assertEquals("Run 3 user", result[0].content)
        assertEquals("Run 3 assistant", result[1].content)
        assertEquals("Run 4 user", result[2].content)
        assertEquals("Run 4 assistant", result[3].content)
        assertEquals("Run 5 user", result[4].content)
        assertEquals("Run 5 assistant", result[5].content)
    }

    @Test
    fun `incomplete final run (no assistant message) is skipped`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dir = createSchedulerDir(tempDir, "task-5")
        dir.resolve("runs.jsonl").writeText(
            // Complete run
            encodeLine(LlmMessage(role = "user", content = "Complete run user")) + "\n" +
                encodeLine(LlmMessage(role = "assistant", content = "Complete run assistant")) + "\n" +
                // Incomplete run — no closing assistant
                encodeLine(LlmMessage(role = "user", content = "Incomplete run user")) + "\n",
        )

        val loader = buildLoader(tempDir)
        val result = loader.loadHistory("task-5", 5)
        // Only the complete run is returned
        assertEquals(2, result.size)
        assertEquals("Complete run user", result[0].content)
        assertEquals("Complete run assistant", result[1].content)
    }

    @Test
    fun `runs spanning multiple JSONL files merged chronologically`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dir = createSchedulerDir(tempDir, "task-6")
        // file1 has an incomplete partial run (only user), file2 completes it and adds run2
        dir.resolve("a_file1.jsonl").writeText(
            encodeLine(LlmMessage(role = "user", content = "Run 1 user")) + "\n",
        )
        dir.resolve("b_file2.jsonl").writeText(
            encodeLine(LlmMessage(role = "assistant", content = "Run 1 assistant")) + "\n" +
                encodeLine(LlmMessage(role = "user", content = "Run 2 user")) + "\n" +
                encodeLine(LlmMessage(role = "assistant", content = "Run 2 assistant")) + "\n",
        )

        val loader = buildLoader(tempDir)
        val result = loader.loadHistory("task-6", 5)
        assertEquals(4, result.size)
        assertEquals("Run 1 user", result[0].content)
        assertEquals("Run 1 assistant", result[1].content)
        assertEquals("Run 2 user", result[2].content)
        assertEquals("Run 2 assistant", result[3].content)
    }

    @Test
    fun `malformed JSONL line skipped, rest of file parsed`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dir = createSchedulerDir(tempDir, "task-7")
        dir.resolve("runs.jsonl").writeText(
            encodeLine(LlmMessage(role = "user", content = "Good user")) + "\n" +
                "NOT_VALID_JSON\n" +
                encodeLine(LlmMessage(role = "assistant", content = "Good assistant")) + "\n",
        )

        val loader = buildLoader(tempDir)
        val result = loader.loadHistory("task-7", 5)
        // The malformed line is skipped, complete run is returned
        assertEquals(2, result.size)
        assertEquals("Good user", result[0].content)
        assertEquals("Good assistant", result[1].content)
    }

    @Test
    fun `tool_call and tool_result messages preserved within run`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dir = createSchedulerDir(tempDir, "task-8")
        val toolCallMsg =
            LlmMessage(
                role = "assistant",
                content = null,
                toolCalls = listOf(ToolCall(id = "call-1", name = "read_file", arguments = "{}")),
            )
        val toolResultMsg = LlmMessage(role = "tool", content = "file content", toolCallId = "call-1")
        val finalAssistant = LlmMessage(role = "assistant", content = "Done reading file")
        dir.resolve("runs.jsonl").writeText(
            encodeLine(LlmMessage(role = "user", content = "Read the file")) + "\n" +
                encodeLine(toolCallMsg) + "\n" +
                encodeLine(toolResultMsg) + "\n" +
                encodeLine(finalAssistant) + "\n",
        )

        val loader = buildLoader(tempDir)
        val result = loader.loadHistory("task-8", 5)
        // One complete run with 4 messages (run ends at final assistant with no tool_calls)
        assertEquals(4, result.size)
        assertEquals("user", result[0].role)
        assertEquals("assistant", result[1].role)
        assertEquals("tool", result[2].role)
        assertEquals("assistant", result[3].role)
        assertEquals("Done reading file", result[3].content)
    }

    @Test
    fun `returns at most n runs when more exist`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dir = createSchedulerDir(tempDir, "task-9")
        val sb = StringBuilder()
        for (i in 1..10) {
            sb.append(encodeLine(LlmMessage(role = "user", content = "User $i"))).append('\n')
            sb.append(encodeLine(LlmMessage(role = "assistant", content = "Assistant $i"))).append('\n')
        }
        dir.resolve("runs.jsonl").writeText(sb.toString())

        val loader = buildLoader(tempDir)
        val result = loader.loadHistory("task-9", 3)
        // Only last 3 runs (6 messages total)
        assertEquals(6, result.size)
        assertEquals("User 8", result[0].content)
        assertEquals("User 10", result[4].content)
    }

    @Test
    fun `empty JSONL file handled gracefully`(
        @TempDir tempDir: Path,
    ) = runTest {
        val dir = createSchedulerDir(tempDir, "task-10")
        dir.resolve("empty.jsonl").writeText("")
        dir.resolve("real.jsonl").writeText(
            encodeLine(LlmMessage(role = "user", content = "User msg")) + "\n" +
                encodeLine(LlmMessage(role = "assistant", content = "Reply")) + "\n",
        )

        val loader = buildLoader(tempDir)
        val result = loader.loadHistory("task-10", 5)
        assertEquals(2, result.size)
        assertEquals("User msg", result[0].content)
        assertEquals("Reply", result[1].content)
    }
}
