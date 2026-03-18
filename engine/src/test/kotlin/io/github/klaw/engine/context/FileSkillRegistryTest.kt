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

    @TempDir
    lateinit var configDir: Path

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

    private fun createRegistry(): FileSkillRegistry =
        FileSkillRegistry(
            dataSkillsDir = dataDir,
            workspaceSkillsDir = workspaceDir,
            workspaceDir = workspaceDir.parent ?: workspaceDir,
            dataDir = dataDir.parent ?: dataDir,
            configDir = configDir,
        )

    @Test
    fun `discovers skills from data dir`() =
        runTest {
            createSkill(dataDir, "test-skill", "A test skill", "# Test\nContent here.")
            val registry = createRegistry()
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
            val registry = createRegistry()
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
            val registry = createRegistry()
            registry.discover()

            val skills = registry.listAll()
            assertEquals(1, skills.size)
            assertEquals("Workspace version", skills[0].description)
        }

    @Test
    fun `getFullContent returns full SKILL md`() =
        runTest {
            createSkill(dataDir, "full", "Full skill", "# Full\nFull content.")
            val registry = createRegistry()
            registry.discover()

            val content = registry.getFullContent("full")
            assertTrue(content != null && content.contains("# Full"), "Expected content but got: $content")
            assertTrue(content!!.contains("Full content."))
        }

    @Test
    fun `getFullContent returns null for unknown skill`() =
        runTest {
            val registry = createRegistry()
            registry.discover()

            assertNull(registry.getFullContent("nonexistent"))
        }

    @Test
    fun `listSkillDescriptions returns description strings`() =
        runTest {
            createSkill(dataDir, "s1", "First skill", "body1")
            createSkill(dataDir, "s2", "Second skill", "body2")
            val registry = createRegistry()
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
            val registry =
                FileSkillRegistry(
                    dataSkillsDir = nonExistent1,
                    workspaceSkillsDir = nonExistent2,
                    workspaceDir = workspaceDir.parent ?: workspaceDir,
                    dataDir = dataDir.parent ?: dataDir,
                    configDir = configDir,
                )
            registry.discover()

            assertEquals(emptyList<SkillMeta>(), registry.listAll())
        }

    // --- Environment variable interpolation tests ---

    @Test
    fun `getFullContent resolves KLAW_WORKSPACE with braces`() =
        runTest {
            val wsRoot = workspaceDir.parent ?: workspaceDir
            createSkill(dataDir, "env1", "Env test", "path: \${KLAW_WORKSPACE}/projects")
            val registry = createRegistry()
            registry.discover()

            val content = registry.getFullContent("env1")!!
            assertTrue(
                content.contains("$wsRoot/projects"),
                "Should resolve \${KLAW_WORKSPACE} to $wsRoot but got: $content",
            )
            assertTrue(
                !content.contains("\${KLAW_WORKSPACE}"),
                "Should not contain raw \${KLAW_WORKSPACE}",
            )
        }

    @Test
    fun `getFullContent resolves KLAW_WORKSPACE without braces`() =
        runTest {
            val wsRoot = workspaceDir.parent ?: workspaceDir
            createSkill(dataDir, "env2", "Env test", "path: \$KLAW_WORKSPACE/data")
            val registry = createRegistry()
            registry.discover()

            val content = registry.getFullContent("env2")!!
            assertTrue(
                content.contains("$wsRoot/data"),
                "Should resolve \$KLAW_WORKSPACE to $wsRoot but got: $content",
            )
            assertTrue(
                !content.contains("\$KLAW_WORKSPACE"),
                "Should not contain raw \$KLAW_WORKSPACE",
            )
        }

    @Test
    fun `getFullContent resolves KLAW_SKILL_DIR variable`() =
        runTest {
            createSkill(dataDir, "env3", "Env test", "run: \${KLAW_SKILL_DIR}/scripts/run.sh")
            val registry = createRegistry()
            registry.discover()

            val content = registry.getFullContent("env3")!!
            val expectedSkillDir = dataDir.resolve("env3")
            assertTrue(
                content.contains("$expectedSkillDir/scripts/run.sh"),
                "Should resolve KLAW_SKILL_DIR to $expectedSkillDir but got: $content",
            )
        }

    @Test
    fun `getFullContent resolves KLAW_DATA variable`() =
        runTest {
            val dataRoot = dataDir.parent ?: dataDir
            createSkill(dataDir, "env4", "Env test", "db: \${KLAW_DATA}/klaw.db")
            val registry = createRegistry()
            registry.discover()

            val content = registry.getFullContent("env4")!!
            assertTrue(
                content.contains("$dataRoot/klaw.db"),
                "Should resolve KLAW_DATA to $dataRoot but got: $content",
            )
        }

    @Test
    fun `getFullContent resolves KLAW_CONFIG variable`() =
        runTest {
            createSkill(dataDir, "env5", "Env test", "config: \$KLAW_CONFIG/engine.json")
            val registry = createRegistry()
            registry.discover()

            val content = registry.getFullContent("env5")!!
            assertTrue(
                content.contains("$configDir/engine.json"),
                "Should resolve KLAW_CONFIG to $configDir but got: $content",
            )
        }

    @Test
    fun `getFullContent resolves multiple variables in one skill`() =
        runTest {
            val wsRoot = workspaceDir.parent ?: workspaceDir
            createSkill(
                dataDir,
                "env6",
                "Env test",
                "ws: \${KLAW_WORKSPACE}/app\ndir: \$KLAW_SKILL_DIR/bin\ndata: \$KLAW_DATA/db",
            )
            val registry = createRegistry()
            registry.discover()

            val content = registry.getFullContent("env6")!!
            assertTrue(content.contains("$wsRoot/app"), "Should resolve KLAW_WORKSPACE")
            assertTrue(
                content.contains("${dataDir.resolve("env6")}/bin"),
                "Should resolve KLAW_SKILL_DIR",
            )
            val dataRoot = dataDir.parent ?: dataDir
            assertTrue(content.contains("$dataRoot/db"), "Should resolve KLAW_DATA")
        }

    @Test
    fun `getFullContent leaves unknown dollar variables unchanged`() =
        runTest {
            createSkill(
                dataDir,
                "env7",
                "Env test",
                "keep: \$UNKNOWN_VAR and \${OTHER_VAR} and \$HOME",
            )
            val registry = createRegistry()
            registry.discover()

            val content = registry.getFullContent("env7")!!
            assertTrue(content.contains("\$UNKNOWN_VAR"), "Should keep \$UNKNOWN_VAR")
            assertTrue(content.contains("\${OTHER_VAR}"), "Should keep \${OTHER_VAR}")
            assertTrue(content.contains("\$HOME"), "Should keep \$HOME")
        }

    @Test
    fun `getFullContent with no variables returns content unchanged`() =
        runTest {
            val body = "# Simple Skill\n\nNo variables here, just plain text."
            createSkill(dataDir, "env8", "Env test", body)
            val registry = createRegistry()
            registry.discover()

            val content = registry.getFullContent("env8")!!
            assertTrue(content.contains(body), "Content should be unchanged: $content")
        }

    @Test
    fun `getFullContent does not greedily match variable name prefixes`() =
        runTest {
            createSkill(
                dataDir,
                "env9",
                "Env test",
                "custom: \$KLAW_DATA_CUSTOM and \$KLAW_WORKSPACE_EXT remain",
            )
            val registry = createRegistry()
            registry.discover()

            val content = registry.getFullContent("env9")!!
            assertTrue(
                content.contains("\$KLAW_DATA_CUSTOM"),
                "Should NOT match \$KLAW_DATA prefix in \$KLAW_DATA_CUSTOM but got: $content",
            )
            assertTrue(
                content.contains("\$KLAW_WORKSPACE_EXT"),
                "Should NOT match \$KLAW_WORKSPACE prefix in \$KLAW_WORKSPACE_EXT but got: $content",
            )
        }
}
