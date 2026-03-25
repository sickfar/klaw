package io.github.klaw.e2e.webui.browser

import com.microsoft.playwright.Page
import io.github.klaw.e2e.infra.WorkspaceGenerator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.io.File

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebUiBrowserSkillsTest : BrowserE2eBase() {
    override fun setupWorkspace(workspace: File) {
        val skillsDir = File(workspace, "skills")
        WorkspaceGenerator.createSkillFile(skillsDir, "test-alpha", "Alpha test skill", "Alpha body")
        WorkspaceGenerator.createSkillFile(skillsDir, "test-beta", "Beta test skill", "Beta body")
    }

    private fun navigateToSkills() {
        page.navigate(baseUrl())
        waitForTestId("app-sidebar")
        page.click("[data-testid='nav-skills']")
        waitForTestId("skills-page")
    }

    private fun waitForSkillRows() {
        page.waitForFunction(
            """
            (() => {
                const rows = document.querySelectorAll('[data-testid^="skill-name-"]');
                return rows.length > 0;
            })()
            """.trimIndent(),
            null,
            Page.WaitForFunctionOptions().setTimeout(LOAD_TIMEOUT),
        )
    }

    @Test
    @Order(1)
    fun `skills page loads and shows heading`() {
        navigateToSkills()
        val text = page.querySelector("[data-testid='skills-page']")?.textContent() ?: ""
        assertTrue(text.contains("Skills"), "Page should contain 'Skills' heading")
    }

    @Test
    @Order(2)
    fun `skills page has refresh button`() {
        navigateToSkills()
        val refresh = page.querySelector("[data-testid='skills-refresh']")
        assertTrue(refresh != null, "Refresh button should exist")
    }

    @Test
    @Order(3)
    fun `skills API returns seeded skills`() {
        val response = restApi.get("/api/v1/skills")
        val json = Json { ignoreUnknownKeys = true }
        val body = json.parseToJsonElement(response.body)
        val skills = body.jsonObject["skills"]?.jsonArray
        assertNotNull(skills, "API response should contain 'skills' array, got: ${response.body.take(MAX_BODY_LOG)}")
        assertTrue(skills!!.size >= 2, "API should return at least 2 skills, got ${skills.size}")

        val names = skills.map { it.jsonObject["name"].toString().trim('"') }
        assertTrue(names.contains("test-alpha"), "API should contain 'test-alpha' skill, got: $names")
        assertTrue(names.contains("test-beta"), "API should contain 'test-beta' skill, got: $names")
    }

    @Test
    @Order(4)
    fun `skills appear in table after loading`() {
        navigateToSkills()
        waitForSkillRows()

        val alpha = page.querySelector("[data-testid='skill-name-test-alpha']")
        assertNotNull(alpha, "Skill 'test-alpha' should appear in the table")
        assertTrue(alpha!!.isVisible, "Skill 'test-alpha' should be visible")

        val beta = page.querySelector("[data-testid='skill-name-test-beta']")
        assertNotNull(beta, "Skill 'test-beta' should appear in the table")
        assertTrue(beta!!.isVisible, "Skill 'test-beta' should be visible")
    }

    @Test
    @Order(5)
    @Suppress("MagicNumber")
    fun `all skills from API match table rows`() {
        val response = restApi.get("/api/v1/skills")
        val json = Json { ignoreUnknownKeys = true }
        val body = json.parseToJsonElement(response.body)
        val apiSkillCount = body.jsonObject["skills"]?.jsonArray?.size ?: 0
        assertTrue(apiSkillCount >= 2, "API should return at least 2 skills, got $apiSkillCount")

        navigateToSkills()
        waitForSkillRows()

        val tableRows = page.querySelectorAll("[data-testid^='skill-name-']")
        assertTrue(
            tableRows.size >= apiSkillCount,
            "Table should show at least $apiSkillCount skills, got ${tableRows.size}",
        )
    }

    @Test
    @Order(6)
    fun `validate button shows result badge`() {
        navigateToSkills()
        waitForSkillRows()

        page.click("[data-testid='skill-validate-test-alpha']")
        waitForTestId("skill-validation-result-test-alpha")

        val badge = page.querySelector("[data-testid='skill-validation-result-test-alpha']")
        assertNotNull(badge, "Validation result badge should appear")
    }

    @Test
    @Order(7)
    fun `refresh button reloads skills list`() {
        navigateToSkills()
        waitForSkillRows()

        page.click("[data-testid='skills-refresh']")

        waitForSkillRows()
        val alpha = page.querySelector("[data-testid='skill-name-test-alpha']")
        assertNotNull(alpha, "Skill 'test-alpha' should still be visible after refresh")
    }

    @Test
    @Order(8)
    fun `validation results clear on refresh`() {
        navigateToSkills()
        waitForSkillRows()

        page.click("[data-testid='skill-validate-test-alpha']")
        waitForTestId("skill-validation-result-test-alpha")

        page.click("[data-testid='skills-refresh']")
        waitForSkillRows()

        val badge = page.querySelector("[data-testid='skill-validation-result-test-alpha']")
        assertNull(badge, "Validation badge should be cleared after refresh")
    }

    companion object {
        private const val LOAD_TIMEOUT = 30_000.0
        private const val MAX_BODY_LOG = 500
    }
}
