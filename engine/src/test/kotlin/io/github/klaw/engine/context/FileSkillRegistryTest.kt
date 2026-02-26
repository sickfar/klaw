package io.github.klaw.engine.context

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class FileSkillRegistryTest {
    @TempDir
    lateinit var dataDir: Path

    @TempDir
    lateinit var workspaceDir: Path

    private fun createSkill(
        baseDir: Path,
        name: String,
        description: String,
        body: String,
    ) {
        val skillDir = baseDir.resolve(name)
        Files.createDirectories(skillDir)
        Files.writeString(
            skillDir.resolve("SKILL.md"),
            "---\nname: $name\ndescription: $description\n---\n$body",
        )
    }

    @Test
    fun `discovers skills from data dir`() =
        runTest {
            createSkill(dataDir, "test-skill", "A test skill", "# Test\nContent here.")
            val registry = FileSkillRegistry(dataDir, workspaceDir)
            registry.discover()

            val skills = registry.listAll()
            assertEquals(1, skills.size)
            assertEquals("test-skill", skills[0].name)
            assertEquals("A test skill", skills[0].description)
        }

    @Test
    fun `discovers skills from workspace dir`() =
        runTest {
            createSkill(workspaceDir, "ws-skill", "Workspace skill", "# WS\nWorkspace content.")
            val registry = FileSkillRegistry(dataDir, workspaceDir)
            registry.discover()

            val skills = registry.listAll()
            assertEquals(1, skills.size)
            assertEquals("ws-skill", skills[0].name)
        }

    @Test
    fun `workspace skill overrides data skill with same name`() =
        runTest {
            createSkill(dataDir, "shared", "Data version", "Data body")
            createSkill(workspaceDir, "shared", "Workspace version", "Workspace body")
            val registry = FileSkillRegistry(dataDir, workspaceDir)
            registry.discover()

            val skills = registry.listAll()
            assertEquals(1, skills.size)
            assertEquals("Workspace version", skills[0].description)
        }

    @Test
    fun `getFullContent returns full SKILL md`() =
        runTest {
            createSkill(dataDir, "full", "Full skill", "# Full\nFull content.")
            val registry = FileSkillRegistry(dataDir, workspaceDir)
            registry.discover()

            val content = registry.getFullContent("full")
            assertTrue(content != null && content.contains("# Full"), "Expected content but got: $content")
            assertTrue(content!!.contains("Full content."))
        }

    @Test
    fun `getFullContent returns null for unknown skill`() =
        runTest {
            val registry = FileSkillRegistry(dataDir, workspaceDir)
            registry.discover()

            assertNull(registry.getFullContent("nonexistent"))
        }

    @Test
    fun `listSkillDescriptions returns description strings`() =
        runTest {
            createSkill(dataDir, "s1", "First skill", "body1")
            createSkill(dataDir, "s2", "Second skill", "body2")
            val registry = FileSkillRegistry(dataDir, workspaceDir)
            registry.discover()

            val descriptions = registry.listSkillDescriptions()
            assertEquals(2, descriptions.size)
            assertTrue(descriptions.any { it.contains("s1") && it.contains("First skill") })
        }

    @Test
    fun `handles missing directories gracefully`() =
        runTest {
            val nonExistent1 = dataDir.resolve("nope1")
            val nonExistent2 = workspaceDir.resolve("nope2")
            val registry = FileSkillRegistry(nonExistent1, nonExistent2)
            registry.discover()

            assertEquals(emptyList<SkillMeta>(), registry.listAll())
        }
}
