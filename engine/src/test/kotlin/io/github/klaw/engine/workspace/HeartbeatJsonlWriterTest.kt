package io.github.klaw.engine.workspace

import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.ToolCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

class HeartbeatJsonlWriterTest {
    @TempDir
    lateinit var conversationsDir: Path

    @Test
    fun `writeDialog creates heartbeat directory and file`() =
        runBlocking {
            val writer = HeartbeatJsonlWriter(conversationsDir)
            writer.writeDialog(listOf(LlmMessage(role = "user", content = "Check weather")), "test/model")

            val heartbeatDir = conversationsDir.resolve("heartbeat")
            assertTrue(Files.exists(heartbeatDir))
            val today = LocalDate.now().toString()
            val file = heartbeatDir.resolve("$today.jsonl")
            assertTrue(Files.exists(file))
            assertTrue(Files.size(file) > 0)
        }

    @Test
    fun `writeDialog skips system messages`() =
        runBlocking {
            val writer = HeartbeatJsonlWriter(conversationsDir)
            writer.writeDialog(
                listOf(
                    LlmMessage(role = "system", content = "You are an AI."),
                    LlmMessage(role = "user", content = "Check weather"),
                ),
                "test/model",
            )

            val lines = readLines()
            assertEquals(1, lines.size)
            val entry = Json.parseToJsonElement(lines[0]).jsonObject
            assertEquals("user", entry["role"]?.jsonPrimitive?.content)
        }

    @Test
    fun `writeDialog writes user message with id and ts`() =
        runBlocking {
            val writer = HeartbeatJsonlWriter(conversationsDir)
            writer.writeDialog(listOf(LlmMessage(role = "user", content = "Check alerts")), "test/model")

            val lines = readLines()
            assertEquals(1, lines.size)
            val entry = Json.parseToJsonElement(lines[0]).jsonObject
            assertEquals("user", entry["role"]?.jsonPrimitive?.content)
            assertEquals("Check alerts", entry["content"]?.jsonPrimitive?.content)
            assertNotNull(entry["id"])
            assertNotNull(entry["ts"])
        }

    @Test
    fun `writeDialog writes assistant text message with model`() =
        runBlocking {
            val writer = HeartbeatJsonlWriter(conversationsDir)
            writer.writeDialog(
                listOf(
                    LlmMessage(role = "user", content = "Check"),
                    LlmMessage(role = "assistant", content = "Nothing to report"),
                ),
                "mymodel/v1",
            )

            val lines = readLines()
            assertEquals(2, lines.size)
            val assistantEntry = Json.parseToJsonElement(lines[1]).jsonObject
            assertEquals("assistant", assistantEntry["role"]?.jsonPrimitive?.content)
            assertEquals("Nothing to report", assistantEntry["content"]?.jsonPrimitive?.content)
            assertEquals("mymodel/v1", assistantEntry["model"]?.jsonPrimitive?.content)
        }

    @Test
    fun `writeDialog writes assistant tool_calls message`() =
        runBlocking {
            val writer = HeartbeatJsonlWriter(conversationsDir)
            writer.writeDialog(
                listOf(
                    LlmMessage(
                        role = "assistant",
                        content = null,
                        toolCalls =
                            listOf(
                                ToolCall(id = "tc1", name = "heartbeat_deliver", arguments = """{"message":"Hi"}"""),
                            ),
                    ),
                ),
                "test/model",
            )

            val lines = readLines()
            assertEquals(1, lines.size)
            val entry = Json.parseToJsonElement(lines[0]).jsonObject
            assertEquals("assistant", entry["role"]?.jsonPrimitive?.content)
            assertNull(entry["content"])
            val toolCalls = entry["tool_calls"]?.jsonArray
            assertNotNull(toolCalls)
            assertEquals(1, toolCalls!!.size)
            val tc = toolCalls[0].jsonObject
            assertEquals("tc1", tc["id"]?.jsonPrimitive?.content)
            assertEquals("heartbeat_deliver", tc["name"]?.jsonPrimitive?.content)
            assertEquals("""{"message":"Hi"}""", tc["arguments"]?.jsonPrimitive?.content)
        }

    @Test
    fun `writeDialog writes tool result message with tool_call_id`() =
        runBlocking {
            val writer = HeartbeatJsonlWriter(conversationsDir)
            writer.writeDialog(
                listOf(LlmMessage(role = "tool", content = "Message queued", toolCallId = "tc1")),
                "test/model",
            )

            val lines = readLines()
            assertEquals(1, lines.size)
            val entry = Json.parseToJsonElement(lines[0]).jsonObject
            assertEquals("tool", entry["role"]?.jsonPrimitive?.content)
            assertEquals("Message queued", entry["content"]?.jsonPrimitive?.content)
            assertEquals("tc1", entry["tool_call_id"]?.jsonPrimitive?.content)
        }

    @Test
    fun `writeDialog appends to existing file on second call`() =
        runBlocking {
            val writer = HeartbeatJsonlWriter(conversationsDir)
            writer.writeDialog(listOf(LlmMessage(role = "user", content = "First run")), "test/model")
            writer.writeDialog(listOf(LlmMessage(role = "user", content = "Second run")), "test/model")

            val lines = readLines()
            assertEquals(2, lines.size)
        }

    @Test
    fun `writeDialog writes nothing when all messages are system`() =
        runBlocking {
            val writer = HeartbeatJsonlWriter(conversationsDir)
            writer.writeDialog(
                listOf(LlmMessage(role = "system", content = "System prompt")),
                "test/model",
            )

            val today = LocalDate.now().toString()
            val file = conversationsDir.resolve("heartbeat").resolve("$today.jsonl")
            assertFalse(Files.exists(file))
        }

    @Test
    fun `writeDialog is safe for concurrent writes`() =
        runBlocking(Dispatchers.Default) {
            val writer = HeartbeatJsonlWriter(conversationsDir)
            val jobs =
                (1..10).map { i ->
                    launch {
                        writer.writeDialog(
                            listOf(LlmMessage(role = "user", content = "Run $i")),
                            "test/model",
                        )
                    }
                }
            jobs.joinAll()

            val lines = readLines()
            assertEquals(10, lines.size)
            lines.forEach { line ->
                val entry = Json.parseToJsonElement(line).jsonObject
                assertNotNull(entry["id"])
                assertNotNull(entry["ts"])
            }
        }

    private fun readLines(): List<String> {
        val today = LocalDate.now().toString()
        val file = conversationsDir.resolve("heartbeat").resolve("$today.jsonl")
        return Files.readAllLines(file).filter { it.isNotBlank() }
    }
}
