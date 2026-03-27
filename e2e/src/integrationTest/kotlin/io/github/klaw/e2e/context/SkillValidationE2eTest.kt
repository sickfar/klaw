package io.github.klaw.e2e.context

import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WebSocketChatClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File

/**
 * E2E tests for `/skills validate` slash command (GitHub issue #12).
 *
 * Verifies that the skill validation command correctly reports:
 * - Valid skills with checkmark
 * - Missing SKILL.md errors
 * - Missing frontmatter errors
 * - Missing required field errors
 * - Correct summary counts
 *
 * Skills setup (workspace):
 * - valid-skill: well-formed SKILL.md with name + description
 * - no-skillmd: directory without SKILL.md
 * - bad-frontmatter: SKILL.md without --- delimiter
 * - no-name: SKILL.md with description but no name field
 * - no-description: SKILL.md with name but no description field
 *
 * Skills setup (data):
 * - data-valid: well-formed data skill (to verify source labeling)
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class SkillValidationE2eTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var client: WebSocketChatClient

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()

        val workspaceDir = WorkspaceGenerator.createWorkspace()
        val skillsDir = File(workspaceDir, "skills")

        // Valid skill
        WorkspaceGenerator.createSkillFile(
            skillsDir,
            "valid-skill",
            "A valid test skill",
            "# Valid Skill\n\nThis skill is well-formed.",
        )

        // Broken: directory without SKILL.md
        val noSkillMdDir = File(skillsDir, "no-skillmd")
        noSkillMdDir.mkdirs()
        noSkillMdDir.setWritable(true, false)
        noSkillMdDir.setReadable(true, false)
        noSkillMdDir.setExecutable(true, false)

        // Broken: SKILL.md without frontmatter delimiter
        val badFrontmatterDir = File(skillsDir, "bad-frontmatter")
        badFrontmatterDir.mkdirs()
        badFrontmatterDir.setWritable(true, false)
        badFrontmatterDir.setReadable(true, false)
        badFrontmatterDir.setExecutable(true, false)
        val badFrontmatterFile = File(badFrontmatterDir, "SKILL.md")
        badFrontmatterFile.writeText("No frontmatter here, just plain text.")
        badFrontmatterFile.setReadable(true, false)

        // Broken: SKILL.md with frontmatter but missing name
        val noNameDir = File(skillsDir, "no-name")
        noNameDir.mkdirs()
        noNameDir.setWritable(true, false)
        noNameDir.setReadable(true, false)
        noNameDir.setExecutable(true, false)
        val noNameFile = File(noNameDir, "SKILL.md")
        noNameFile.writeText("---\ndescription: Has description but no name\n---\n# Body")
        noNameFile.setReadable(true, false)

        // Broken: SKILL.md with frontmatter but missing description
        val noDescDir = File(skillsDir, "no-description")
        noDescDir.mkdirs()
        noDescDir.setWritable(true, false)
        noDescDir.setReadable(true, false)
        noDescDir.setExecutable(true, false)
        val noDescFile = File(noDescDir, "SKILL.md")
        noDescFile.writeText("---\nname: no-description\n---\n# Body")
        noDescFile.setReadable(true, false)

        val wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}"

        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = wiremockBaseUrl,
                        tokenBudget = CONTEXT_BUDGET_TOKENS,
                        summarizationEnabled = false,
                        autoRagEnabled = false,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(),
                workspaceDir = workspaceDir,
            )
        containers.start()

        // Create data (global) skill after start — to test source labeling
        WorkspaceGenerator.createSkillFile(
            File(containers.engineDataPath, "skills"),
            "data-valid",
            "A valid data skill",
            "# Data Skill\n\nGlobal data skill body.",
        )

        client = WebSocketChatClient()
        client.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
    }

    @AfterAll
    fun stopInfrastructure() {
        client.close()
        containers.stop()
        wireMock.stop()
    }

    @Test
    @Order(1)
    fun `valid skill reported as valid`() {
        val response = client.sendCommandAndReceive("skills validate", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("valid-skill") && response.contains("valid"),
            "Response should report valid-skill as valid, but was: $response",
        )
    }

    @Test
    @Order(2)
    fun `missing SKILL md reported as error`() {
        val response = client.sendCommandAndReceive("skills validate", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("no-skillmd") && response.contains("missing SKILL.md"),
            "Response should report no-skillmd with 'missing SKILL.md' error, but was: $response",
        )
    }

    @Test
    @Order(3)
    fun `missing frontmatter reported as error`() {
        val response = client.sendCommandAndReceive("skills validate", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("bad-frontmatter") && response.contains("missing frontmatter"),
            "Response should report bad-frontmatter with 'missing frontmatter' error, but was: $response",
        )
    }

    @Test
    @Order(4)
    fun `missing name field reported as error`() {
        val response = client.sendCommandAndReceive("skills validate", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("no-name") && response.contains("missing required field 'name'"),
            "Response should report no-name with missing name error, but was: $response",
        )
    }

    @Test
    @Order(5)
    fun `missing description field reported as error`() {
        val response = client.sendCommandAndReceive("skills validate", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("no-description") && response.contains("missing required field 'description'"),
            "Response should report no-description with missing description error, but was: $response",
        )
    }

    @Test
    @Order(6)
    fun `summary line shows correct counts`() {
        val response = client.sendCommandAndReceive("skills validate", timeoutMs = RESPONSE_TIMEOUT_MS)

        // 6 total skills: valid-skill, no-skillmd, bad-frontmatter, no-name, no-description (workspace) + data-valid (data)
        // 2 valid (valid-skill + data-valid), 4 errors
        assertTrue(
            response.contains("6 skills checked") && response.contains("4 error"),
            "Response should contain '6 skills checked' and '4 error', but was: $response",
        )
    }

    @Test
    @Order(7)
    fun `data skill source labeled correctly`() {
        val response = client.sendCommandAndReceive("skills validate", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("data-valid") && response.contains("data"),
            "Response should show data-valid with source 'data', but was: $response",
        )
    }

    @Test
    @Order(8)
    fun `workspace skill source labeled correctly`() {
        val response = client.sendCommandAndReceive("skills validate", timeoutMs = RESPONSE_TIMEOUT_MS)

        assertTrue(
            response.contains("valid-skill") && response.contains("workspace"),
            "Response should show valid-skill with source 'workspace', but was: $response",
        )
    }

    companion object {
        private const val CONTEXT_BUDGET_TOKENS = 5000
        private const val RESPONSE_TIMEOUT_MS = 30_000L
    }
}
