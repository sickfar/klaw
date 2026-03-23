package io.github.klaw.e2e.webui.browser

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebUiBrowserDashboardTest : BrowserE2eBase() {
    @Test
    fun `dashboard page loads`() {
        page.navigate("${baseUrl()}/dashboard")
        waitForTestId("dashboard-page")
        assertNotNull(page.querySelector("[data-testid='dashboard-page']"))
    }

    @Test
    fun `dashboard shows status info`() {
        page.navigate("${baseUrl()}/dashboard")
        waitForTestId("stat-status")
        val text = page.querySelector("[data-testid='dashboard-page']")!!.textContent()
        assertNotNull(text)
    }

    @Test
    fun `dashboard shows status ok badge`() {
        page.navigate("${baseUrl()}/dashboard")
        page.waitForFunction(
            "document.querySelector('[data-testid=\"stat-status\"]')?.textContent?.toLowerCase()?.includes('ok')",
        )
        val statusText = page.querySelector("[data-testid='stat-status']")!!.textContent()
        assertTrue(statusText.contains("ok", ignoreCase = true), "Status should contain 'ok', got: $statusText")
    }

    @Test
    fun `dashboard shows uptime value`() {
        page.navigate("${baseUrl()}/dashboard")
        waitForTestId("stat-uptime")
        val uptimeText = page.querySelector("[data-testid='stat-uptime']")!!.textContent().trim()
        assertTrue(uptimeText != "-" && uptimeText.isNotEmpty(), "Uptime should have a value, got: '$uptimeText'")
    }

    @Test
    fun `dashboard refresh button reloads data`() {
        page.navigate("${baseUrl()}/dashboard")
        waitForTestId("dashboard-refresh")
        page.click("[data-testid='dashboard-refresh']")
        waitForTestId("last-updated")
        assertNotNull(page.querySelector("[data-testid='last-updated']"))
    }

    @Test
    fun `dashboard shows last updated timestamp`() {
        page.navigate("${baseUrl()}/dashboard")
        waitForTestId("last-updated")
        val text = page.querySelector("[data-testid='last-updated']")!!.textContent()
        assertTrue(text.contains("Last updated"), "Should show 'Last updated', got: $text")
    }
}
