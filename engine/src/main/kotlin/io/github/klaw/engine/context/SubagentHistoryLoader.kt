package io.github.klaw.engine.context

import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

private val logger = KotlinLogging.logger {}

@Singleton
class SubagentHistoryLoader(
    private val conversationsDir: String = KlawPaths.conversations,
) {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Loads the last [n] complete runs from the scheduler JSONL files for [taskName].
     * Files in `$conversationsDir/scheduler_$taskName/` are read in lexicographic order.
     * A run is complete when its last message has role=="assistant" and no tool calls.
     * Incomplete final runs (no closing assistant) are discarded.
     * Returns messages from the last [n] runs, oldest run first.
     */
    suspend fun loadHistory(
        taskName: String,
        n: Int,
    ): List<LlmMessage> =
        withContext(Dispatchers.VT) {
            val base = File(conversationsDir).canonicalFile
            val dir = File("$conversationsDir/scheduler_$taskName").canonicalFile
            // Path traversal protection
            if (!dir.path.startsWith(base.path)) return@withContext emptyList()
            if (!dir.isDirectory) return@withContext emptyList()

            val files =
                dir
                    .listFiles { f -> f.name.endsWith(".jsonl") }
                    ?.sortedBy { it.name }
                    ?: return@withContext emptyList()

            val allMessages = mutableListOf<LlmMessage>()
            for (file in files) {
                for (line in file.readLines()) {
                    if (line.isBlank()) continue
                    try {
                        allMessages.add(json.decodeFromString<LlmMessage>(line))
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        logger.warn { "Skipping malformed JSONL in ${file.name}: ${e::class.simpleName}" }
                    }
                }
            }

            val runs = detectRuns(allMessages)
            val lastN = runs.takeLast(n)
            lastN.flatten()
        }

    /**
     * Groups messages into complete runs.
     * A run ends when role=="assistant" and toolCalls is null or empty.
     * Incomplete trailing run (no closing assistant) is discarded.
     */
    private fun detectRuns(messages: List<LlmMessage>): List<List<LlmMessage>> {
        val runs = mutableListOf<List<LlmMessage>>()
        var currentRun = mutableListOf<LlmMessage>()

        for (msg in messages) {
            currentRun.add(msg)
            if (msg.role == "assistant" && msg.toolCalls.isNullOrEmpty()) {
                runs.add(currentRun.toList())
                currentRun = mutableListOf()
            }
        }
        // currentRun (if non-empty) is an incomplete run — discard it

        return runs
    }
}
