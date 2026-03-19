package io.github.klaw.engine.context

import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

private val logger = KotlinLogging.logger {}

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
        val source: String,
        val filePath: Path? = null,
        val classpathResource: String? = null,
    )

    override fun discover() {
        logger.trace { "Skill discovery started" }
        skills.clear()
        scanBundledSkills()
        scanDir(dataSkillsDir, "data")
        scanDir(workspaceSkillsDir, "workspace")
        logger.debug { "Skill discovery complete: ${skills.size} skills" }
    }

    private fun scanBundledSkills() {
        val index =
            javaClass.classLoader
                .getResourceAsStream("klaw-skills/skills-index.txt")
                ?.use { it.bufferedReader().readText() }
                ?.lines()
                ?.filter { it.isNotBlank() }
                ?: return

        var count = 0
        for (skillName in index) {
            val resourcePath = "klaw-skills/$skillName/SKILL.md"
            val content =
                javaClass.classLoader
                    .getResourceAsStream(resourcePath)
                    ?.use { it.bufferedReader().readText() }
            if (content == null) {
                logger.trace { "Skipped bundled skill entry: $skillName (no content)" }
                continue
            }
            val meta = parseFrontmatterFromContent(content)
            if (meta == null) {
                logger.trace { "Skipped bundled skill entry: $skillName (invalid frontmatter)" }
                continue
            }
            skills[meta.name] = SkillEntry(meta = meta, source = "bundled", classpathResource = resourcePath)
            logger.trace { "Loaded bundled skill: ${meta.name}" }
            count++
        }
        logger.debug { "Scanned bundled skills: $count loaded" }
    }

    private fun scanDir(
        dir: Path,
        source: String,
    ) {
        if (!Files.exists(dir) || !Files.isDirectory(dir)) {
            logger.trace { "Skill directory not found, skipping: $dir" }
            return
        }
        var count = 0
        Files.list(dir).use { stream ->
            stream
                .filter { Files.isDirectory(it) }
                .forEach { skillDir ->
                    if (processSkillDir(skillDir, source)) count++
                }
        }
        logger.debug { "Scanned $source skills: $count found in $dir" }
    }

    private fun processSkillDir(
        skillDir: Path,
        source: String,
    ): Boolean {
        val skillFile = skillDir.resolve("SKILL.md")
        if (!Files.exists(skillFile)) {
            logger.trace { "Skipped ${skillDir.fileName}: missing SKILL.md" }
            return false
        }
        val meta = parseFrontmatter(skillFile)
        if (meta == null) {
            logger.trace { "Skipped ${skillDir.fileName}: invalid frontmatter" }
            return false
        }
        if (skills.containsKey(meta.name)) {
            logger.trace { "Overriding skill ${meta.name} with $source version from ${skillDir.fileName}" }
        }
        skills[meta.name] = SkillEntry(meta, source, skillFile)
        logger.trace { "Loaded skill: ${meta.name} from ${skillDir.fileName} ($source)" }
        return true
    }

    private fun parseFrontmatterFromContent(content: String): SkillMeta? {
        val lines = content.lines()
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
        return if (name != null && description != null) SkillMeta(name, description) else null
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

    override suspend fun listDetailed(): List<SkillDetail> =
        skills.values.map { SkillDetail(it.meta.name, it.meta.description, it.source) }

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
            if (entry.filePath != null) {
                withContext(Dispatchers.VT) { Files.readString(entry.filePath) }
            } else if (entry.classpathResource != null) {
                javaClass.classLoader
                    .getResourceAsStream(entry.classpathResource)
                    ?.use { it.bufferedReader().readText() }
                    ?: return null
            } else {
                return null
            }
        val skillDir = entry.filePath?.parent ?: workspaceDir
        return interpolateVariables(rawContent, skillDir)
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
