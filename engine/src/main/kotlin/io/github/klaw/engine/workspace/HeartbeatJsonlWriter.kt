package io.github.klaw.engine.workspace

import io.github.klaw.common.llm.LlmMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Clock
import java.time.LocalDate
import java.util.UUID
import kotlin.time.toKotlinInstant

private val logger = KotlinLogging.logger {}

class HeartbeatJsonlWriter(
    private val conversationsDir: Path,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val mutex = Mutex()

    suspend fun writeDialog(
        messages: List<LlmMessage>,
        model: String,
    ) {
        val filtered = messages.filter { it.role != "system" }
        if (filtered.isEmpty()) return

        val lines = filtered.map { buildEntryLine(it, model) }
        val file = resolveFile()
        Files.createDirectories(file.parent)

        mutex.withLock {
            Files.writeString(
                file,
                lines.joinToString("\n") + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND,
            )
            logger.trace { "Heartbeat JSONL written: entries=${lines.size}" }
        }
    }

    private fun resolveFile(): Path {
        val date = LocalDate.now(clock).toString()
        return conversationsDir.resolve("heartbeat").resolve("$date.jsonl")
    }

    private fun buildEntryLine(
        msg: LlmMessage,
        model: String,
    ): String {
        val json =
            buildJsonObject {
                put("id", UUID.randomUUID().toString())
                put("ts", clock.instant().toKotlinInstant().toString())
                put("role", msg.role)
                when {
                    msg.role == "tool" -> {
                        if (msg.toolCallId != null) put("tool_call_id", msg.toolCallId)
                        if (msg.content != null) put("content", msg.content)
                    }

                    !msg.toolCalls.isNullOrEmpty() -> {
                        val toolCalls = msg.toolCalls!!
                        put("model", model)
                        putJsonArray("tool_calls") {
                            toolCalls.forEach { tc ->
                                addJsonObject {
                                    put("id", tc.id)
                                    put("name", tc.name)
                                    put("arguments", tc.arguments)
                                }
                            }
                        }
                    }

                    else -> {
                        if (msg.content != null) put("content", msg.content)
                        if (msg.role == "assistant") put("model", model)
                    }
                }
            }
        return json.toString()
    }
}
