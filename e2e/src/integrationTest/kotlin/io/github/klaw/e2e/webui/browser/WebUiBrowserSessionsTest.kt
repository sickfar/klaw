package io.github.klaw.e2e.webui.browser

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebUiBrowserSessionsTest : BrowserE2eBase() {
    private fun navigateToSessions() {
        page.navigate(baseUrl())
        waitForTestId("app-sidebar")
        page.click("[data-testid='nav-sessions']")
        waitForTestId("sessions-page")
    }

    @Test
    fun `sessions page loads`() {
        navigateToSessions()
    }

    @Test
    fun `sessions page has refresh and cleanup buttons`() {
        navigateToSessions()
        assertNotNull(page.querySelector("[data-testid='sessions-refresh']"), "Refresh button should exist")
        assertNotNull(page.querySelector("[data-testid='sessions-cleanup']"), "Cleanup button should exist")
    }

    @Test
    fun `sessions page shows content after API response`() {
        navigateToSessions()
        // Wait for either sessions data or empty state or error
        page.waitForFunction(
            """
            (() => {
                const el = document.querySelector('[data-testid="sessions-page"]');
                if (!el) return false;
                const text = el.textContent || '';
                return text.includes('No active sessions') || text.includes('Chat ID') ||
                       document.querySelector('[data-testid="sessions-error"]') !== null;
            })()
            """.trimIndent(),
        )
    }
}
