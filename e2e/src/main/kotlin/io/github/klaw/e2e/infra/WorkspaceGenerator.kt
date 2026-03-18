package io.github.klaw.e2e.infra

import java.io.File
import kotlin.io.path.createTempDirectory

object WorkspaceGenerator {
    fun createWorkspace(baseDir: File? = null): File {
        val dir =
            if (baseDir != null) {
                baseDir.apply { mkdirs() }
            } else {
                createTempDirectory("klaw-e2e-workspace").toFile()
            }

        File(dir, "SOUL.md").writeText("You are a test assistant. Be brief.")
        File(dir, "IDENTITY.md").writeText("E2E Test Bot")
        return dir
    }

    fun createSkillFile(
        skillsBaseDir: File,
        name: String,
        description: String,
        body: String,
    ) {
        val skillDir = File(skillsBaseDir, name)
        skillDir.mkdirs()
        skillDir.setWritable(true, false)
        skillDir.setReadable(true, false)
        skillDir.setExecutable(true, false)
        val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText("---\nname: $name\ndescription: $description\n---\n$body")
        skillFile.setReadable(true, false)
    }
}
