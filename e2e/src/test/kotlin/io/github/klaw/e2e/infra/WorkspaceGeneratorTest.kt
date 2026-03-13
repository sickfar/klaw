package io.github.klaw.e2e.infra

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class WorkspaceGeneratorTest {
    @Test
    fun `workspace dir contains SOUL md and IDENTITY md`() {
        val workspaceDir = WorkspaceGenerator.createWorkspace()

        try {
            val soulFile = File(workspaceDir, "SOUL.md")
            val identityFile = File(workspaceDir, "IDENTITY.md")

            assertTrue(soulFile.exists(), "SOUL.md should exist")
            assertTrue(identityFile.exists(), "IDENTITY.md should exist")
            assertTrue(soulFile.readText().isNotBlank(), "SOUL.md should have content")
            assertTrue(identityFile.readText().isNotBlank(), "IDENTITY.md should have content")
        } finally {
            workspaceDir.deleteRecursively()
        }
    }
}
