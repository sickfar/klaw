package io.github.klaw.engine.context

import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

/**
 * Loads OpenClaw-compatible workspace files into a cached system prompt.
 *
 * On [initialize]:
 * - Builds system prompt from SOUL.md to IDENTITY.md to AGENTS.md to TOOLS.md
 * - Populates core memory user section from USER.md on first run (when empty)
 * - Indexes MEMORY.md + daily memory logs into sqlite-vec via [MemoryService.save]
 *
 * USER.md is NOT included in the system prompt — it populates core_memory.json once.
 * System prompt is cached after initialize; not re-read per message.
 */
class KlawWorkspaceLoader(
    private val workspacePath: Path,
    private val memoryService: MemoryService,
    private val coreMemory: CoreMemoryService,
) : WorkspaceLoader {
    private var cachedSystemPrompt: String = ""

    fun initialize() {
        if (!Files.isDirectory(workspacePath)) {
            logger.debug { "Workspace dir not found — skipping workspace load path=$workspacePath" }
            return
        }
        runBlocking {
            cachedSystemPrompt = buildSystemPrompt()
            initCoreMemoryFromUserMd()
            indexMemoryFiles()
        }
        logger.info { "Workspace loaded path=$workspacePath" }
    }

    override suspend fun loadSystemPrompt(): String = cachedSystemPrompt

    private fun buildSystemPrompt(): String {
        val sections =
            listOf(
                "SOUL.md" to "## Soul",
                "IDENTITY.md" to "## Identity",
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
        return parts.joinToString("\n\n")
    }

    private suspend fun initCoreMemoryFromUserMd() {
        val userMd = workspacePath.resolve("USER.md")
        if (!Files.exists(userMd)) return
        val json = coreMemory.getJson()
        val userSection =
            runCatching {
                Json.parseToJsonElement(json).jsonObject["user"]?.jsonObject
            }.getOrNull() ?: return
        if (userSection.isNotEmpty()) {
            logger.debug { "Core memory user section already populated — skipping USER.md init" }
            return
        }
        val content = withContext(Dispatchers.VT) { Files.readString(userMd).trim() }
        if (content.isNotEmpty()) {
            coreMemory.update("user", "notes", content)
            logger.debug { "Core memory user section initialized from USER.md" }
        }
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
            withContext(Dispatchers.VT) {
                Files.list(memoryDir).use { stream ->
                    stream.filter { it.fileName.toString().endsWith(".md") }.forEach { file ->
                        val content = Files.readString(file)
                        runBlocking { memoryService.save(content, file.fileName.toString()) }
                        logger.debug { "Indexed memory file name=${file.fileName}" }
                    }
                }
            }
        }
    }
}
