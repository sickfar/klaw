package io.github.klaw.engine.context

import io.github.klaw.engine.util.VT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

class FileSkillRegistry(
    private val dataSkillsDir: Path,
    private val workspaceSkillsDir: Path,
) : SkillRegistry {
    private val skills = mutableMapOf<String, SkillEntry>()

    private data class SkillEntry(
        val meta: SkillMeta,
        val filePath: Path,
    )

    fun discover() {
        skills.clear()
        scanDir(dataSkillsDir)
        scanDir(workspaceSkillsDir) // workspace overrides data
    }

    private fun scanDir(dir: Path) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return
        Files.list(dir).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .forEach { skillDir -> processSkillDir(skillDir) }
        }
    }

    private fun processSkillDir(skillDir: Path) {
        val skillFile = skillDir.resolve("SKILL.md")
        if (!Files.exists(skillFile)) return
        val meta = parseFrontmatter(skillFile) ?: return
        skills[meta.name] = SkillEntry(meta, skillFile)
    }

    @Suppress("ReturnCount")
    private fun parseFrontmatter(file: Path): SkillMeta? {
        val lines = Files.readAllLines(file)
        if (lines.isEmpty() || lines[0].trim() != "---") return null

        var name: String? = null
        var description: String? = null
        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line == "---") break
            when {
                line.startsWith("name:") -> name = line.removePrefix("name:").trim()
                line.startsWith("description:") -> description = line.removePrefix("description:").trim()
            }
        }
        if (name == null || description == null) return null
        return SkillMeta(name, description)
    }

    override suspend fun listSkillDescriptions(): List<String> =
        skills.values.map {
            "${it.meta.name}: ${it.meta.description}"
        }

    override suspend fun listAll(): List<SkillMeta> = skills.values.map { it.meta }

    override suspend fun getFullContent(name: String): String? {
        val entry = skills[name] ?: return null
        return withContext(Dispatchers.VT) {
            Files.readString(entry.filePath)
        }
    }
}
