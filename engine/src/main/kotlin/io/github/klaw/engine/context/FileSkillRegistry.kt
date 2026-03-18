package io.github.klaw.engine.context

import io.github.klaw.engine.util.VT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

class FileSkillRegistry(
    private val dataSkillsDir: Path,
    private val workspaceSkillsDir: Path,
    private val workspaceDir: Path,
    private val dataDir: Path,
    private val configDir: Path,
) : SkillRegistry {
    private val skills = mutableMapOf<String, SkillEntry>()

    private data class SkillEntry(
        val meta: SkillMeta,
        val filePath: Path,
    )

    override fun discover() {
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

    private fun parseFrontmatter(file: Path): SkillMeta? {
        val lines = Files.readAllLines(file)
        if (lines.isEmpty() || lines[0].trim() != "---") {
            return null
        }

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
        return if (name != null && description != null) SkillMeta(name, description) else null
    }

    override suspend fun listSkillDescriptions(): List<String> =
        skills.values.map {
            "${it.meta.name}: ${it.meta.description}"
        }

    override suspend fun listAll(): List<SkillMeta> = skills.values.map { it.meta }

    override suspend fun validate(): SkillValidationReport =
        withContext(Dispatchers.VT) {
            val entries = mutableListOf<SkillValidationEntry>()
            validateDir(dataSkillsDir, "data", entries)
            validateDir(workspaceSkillsDir, "workspace", entries)
            SkillValidationReport(entries)
        }

    private fun validateDir(
        dir: Path,
        source: String,
        entries: MutableList<SkillValidationEntry>,
    ) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) return
        Files.list(dir).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .forEach { skillDir -> entries.add(validateSkillDir(skillDir, source)) }
        }
    }

    private fun validateSkillDir(
        skillDir: Path,
        source: String,
    ): SkillValidationEntry {
        val dirName = skillDir.fileName.toString()
        val skillFile = skillDir.resolve("SKILL.md")
        if (!Files.exists(skillFile)) {
            return SkillValidationEntry(null, dirName, source, false, "missing SKILL.md")
        }
        val fields = parseFrontmatterFields(skillFile)
        val error =
            when {
                fields == null -> "missing frontmatter"
                fields.first == null -> "missing required field 'name'"
                fields.second == null -> "missing required field 'description'"
                else -> null
            }
        return SkillValidationEntry(
            name = fields?.first,
            directory = dirName,
            source = source,
            valid = error == null,
            error = error,
        )
    }

    private fun parseFrontmatterFields(file: Path): Pair<String?, String?>? {
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
        return name to description
    }

    override suspend fun getFullContent(name: String): String? {
        val entry = skills[name] ?: return null
        val rawContent =
            withContext(Dispatchers.VT) {
                Files.readString(entry.filePath)
            }
        return interpolateVariables(rawContent, entry.filePath.parent)
    }

    private fun interpolateVariables(
        content: String,
        skillDir: Path,
    ): String {
        val bindings =
            mapOf(
                "KLAW_WORKSPACE" to workspaceDir.toString(),
                "KLAW_SKILL_DIR" to skillDir.toString(),
                "KLAW_DATA" to dataDir.toString(),
                "KLAW_CONFIG" to configDir.toString(),
            )
        var result = content
        for ((name, value) in bindings) {
            result = result.replace("\${$name}", value)
            val unbracedPattern = Regex("""\$${Regex.escape(name)}(?![A-Za-z0-9_])""")
            result = unbracedPattern.replace(result, Regex.escapeReplacement(value))
        }
        return result
    }
}
