package io.github.klaw.engine.context

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.engine.memory.MemoryService
import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private val logger = KotlinLogging.logger {}

/**
 * Loads workspace files into a cached system prompt and manages memory indexation.
 *
 * On [initialize]:
 * - Builds system prompt from SOUL.md → IDENTITY.md → USER.md → AGENTS.md → TOOLS.md
 * - On first start: indexes MEMORY.md and daily memory logs into DB with categories, then archives files
 * - Builds memory summary from DB (cached, invalidated on memory_save)
 */
class KlawWorkspaceLoader(
    private val workspacePath: Path,
    private val memoryService: MemoryService,
    private val config: EngineConfig,
) : WorkspaceLoader {
    @Volatile
    private var cachedSystemPrompt: String = ""

    @Volatile
    private var cachedMemorySummary: String? = null

    fun initialize() {
        if (!Files.isDirectory(workspacePath)) {
            logger.debug { "Workspace dir not found — skipping workspace load path=$workspacePath" }
            return
        }
        runBlocking {
            cachedSystemPrompt = buildSystemPrompt()
            indexMemoryFilesIfNeeded()
            cachedMemorySummary = buildMemorySummaryFromDb()
        }
        logger.info { "Workspace loaded path=$workspacePath" }
    }

    override suspend fun loadSystemPrompt(): String = cachedSystemPrompt

    override suspend fun loadMemorySummary(): String? = cachedMemorySummary

    fun invalidateMemorySummaryCache() {
        cachedMemorySummary = null
    }

    suspend fun refreshMemorySummary() {
        cachedMemorySummary = buildMemorySummaryFromDb()
    }

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

    private suspend fun indexMemoryFilesIfNeeded() {
        if (memoryService.hasCategories()) {
            logger.debug { "Categories exist in DB — skipping file indexation" }
            return
        }

        val filesToArchive = mutableListOf<File>()
        val memoryMd = workspacePath.resolve("MEMORY.md")
        if (Files.exists(memoryMd)) {
            val content = withContext(Dispatchers.VT) { Files.readString(memoryMd) }
            indexMarkdownContent(content, "MEMORY.md")
            filesToArchive.add(memoryMd.toFile())
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
                indexMarkdownContent(content, file.fileName.toString())
                filesToArchive.add(file.toFile())
            }
        }

        if (filesToArchive.isNotEmpty()) {
            archiveAndDeleteFiles(filesToArchive)
        }
    }

    private suspend fun indexMarkdownContent(
        content: String,
        source: String,
    ) {
        val categorizedFacts = MemorySummaryExtractor.extractCategorizedFacts(content)
        if (categorizedFacts.isEmpty()) {
            logger.debug { "No categorized facts found in $source" }
            return
        }

        for ((category, facts) in categorizedFacts) {
            for (fact in facts) {
                memoryService.save(content = fact, category = category, source = source)
            }
        }
        val totalFacts = categorizedFacts.values.sumOf { it.size }
        logger.debug { "Indexed $source: ${categorizedFacts.size} categories, $totalFacts facts" }
    }

    private suspend fun archiveAndDeleteFiles(files: List<File>) {
        withContext(Dispatchers.VT) {
            val zipFile = workspacePath.resolve("memory-archive.zip").toFile()
            ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
                for (file in files) {
                    val entryName =
                        workspacePath
                            .toFile()
                            .toPath()
                            .relativize(file.toPath())
                            .toString()
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
            for (file in files) {
                file.delete()
            }
            // Clean up empty memory directory
            val memoryDir = workspacePath.resolve("memory").toFile()
            if (memoryDir.isDirectory && memoryDir.listFiles()?.isEmpty() == true) {
                memoryDir.delete()
            }
            logger.info { "Archived ${files.size} memory file(s) to memory-archive.zip" }
        }
    }

    private suspend fun buildMemorySummaryFromDb(): String? {
        if (!config.memory.injectSummary) return null

        val limit = config.memory.mapMaxCategories
        val categories = memoryService.getTopCategories(limit)
        if (categories.isEmpty()) return null

        val totalCount = memoryService.getTotalCategoryCount()
        val remaining = totalCount - categories.size

        return buildString {
            append("## Memory Map\n")
            append("Your long-term memory contains the following topics. ")
            append("Use `memory_search` to retrieve details.\n")
            categories.forEachIndexed { index, cat ->
                if (index > 0) append("\n")
                append("- ${cat.name} (${cat.entryCount} entries)")
            }
            if (remaining > 0) {
                append("\n...and $remaining more categories")
            }
        }
    }
}
