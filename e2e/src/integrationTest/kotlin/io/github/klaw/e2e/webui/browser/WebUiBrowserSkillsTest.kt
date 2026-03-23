package io.github.klaw.e2e.webui.browser

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebUiBrowserSkillsTest : BrowserE2eBase() {
    private fun navigateToSkills() {
        page.navigate(baseUrl())
        waitForTestId("app-sidebar")
        page.click("[data-testid='nav-skills']")
        waitForTestId("skills-page")
    }

    @Test
    fun `skills page loads and shows heading`() {
        navigateToSkills()
        val text = page.querySelector("[data-testid='skills-page']")?.textContent() ?: ""
        assertTrue(text.contains("Skills"), "Page should contain 'Skills' heading")
    }

    @Test
    fun `skills page has refresh button`() {
        navigateToSkills()
        val refresh = page.querySelector("[data-testid='skills-refresh']")
        assertTrue(refresh != null, "Refresh button should exist")
    }
}
