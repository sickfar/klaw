package io.github.klaw.e2e.infra

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
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

    fun createMemoryMd(
        workspaceDir: File,
        content: String,
    ) {
        val memoryFile = File(workspaceDir, "MEMORY.md")
        memoryFile.writeText(content)
        memoryFile.setReadable(true, false)
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

    fun createPdfFile(
        workspaceDir: File,
        name: String,
        pages: List<String>,
    ) {
        val pdfFile = File(workspaceDir, name)
        PDDocument().use { doc ->
            val font =
                org.apache.pdfbox.pdmodel.font
                    .PDType1Font(Standard14Fonts.FontName.HELVETICA)
            for (pageText in pages) {
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(font, FONT_SIZE)
                    cs.newLineAtOffset(MARGIN_X, MARGIN_Y)
                    cs.showText(pageText)
                    cs.endText()
                }
            }
            doc.save(pdfFile)
        }
        pdfFile.setReadable(true, false)
    }

    private const val FONT_SIZE = 12f
    private const val MARGIN_X = 72f
    private const val MARGIN_Y = 700f
}
