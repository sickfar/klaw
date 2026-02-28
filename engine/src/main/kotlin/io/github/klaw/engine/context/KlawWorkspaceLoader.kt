package io.github.klaw.engine.context

import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Loads OpenClaw-compatible workspace files into a cached system prompt.
 *
 * On [initialize]:
 * - Builds system prompt from SOUL.md → IDENTITY.md → USER.md → AGENTS.md → TOOLS.md
 * - Indexes MEMORY.md + daily memory logs into sqlite-vec via [MemoryService.save]
 *
 * System prompt is cached after initialize; not re-read per message.
 */
class KlawWorkspaceLoader(
    private val workspacePath: Path,
    private val memoryService: MemoryService,
) : WorkspaceLoader {
    @Volatile
    private var cachedSystemPrompt: String = ""

    fun initialize() {
        if (!Files.isDirectory(workspacePath)) {
            logger.debug { "Workspace dir not found — skipping workspace load path=$workspacePath" }
            return
        }
        runBlocking {
            cachedSystemPrompt = buildSystemPrompt()
            indexMemoryFiles()
        }
        logger.info { "Workspace loaded path=$workspacePath" }
    }

    override suspend fun loadSystemPrompt(): String = cachedSystemPrompt

    private suspend fun buildSystemPrompt(): String =
        withContext(Dispatchers.VT) {
            val sections =
                listOf(
                    "SOUL.md" to "## Soul",
                    "IDENTITY.md" to "## Identity",
                    "USER.md" to "## About the User",
                    "AGENTS.md" to "## Instructions",
                    "TOOLS.md" to "## Environment Notes",
                )
            val parts = mutableListOf<String>()
            sections.forEach { (filename, header) ->
                val file = workspacePath.resolve(filename)
                if (Files.exists(file)) {
                    val content = Files.readString(file).trim()
                    if (content.isNotEmpty()) {
                        parts.add("$header\n$content")
                    }
                }
            }
            parts.joinToString("\n\n")
        }

    private suspend fun indexMemoryFiles() {
        val memoryMd = workspacePath.resolve("MEMORY.md")
        if (Files.exists(memoryMd)) {
            val content = withContext(Dispatchers.VT) { Files.readString(memoryMd) }
            memoryService.save(content, "MEMORY.md")
            logger.debug { "Indexed MEMORY.md" }
        }
        val memoryDir = workspacePath.resolve("memory")
        if (Files.isDirectory(memoryDir)) {
            val files =
                withContext(Dispatchers.VT) {
                    Files.list(memoryDir).use { stream ->
                        stream.filter { it.fileName.toString().endsWith(".md") }.toList()
                    }
                }
            for (file in files) {
                val content = withContext(Dispatchers.VT) { Files.readString(file) }
                memoryService.save(content, file.fileName.toString())
                logger.debug { "Indexed memory file name=${file.fileName}" }
            }
        }
    }
}
