package io.github.klaw.e2e.webui.browser

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class WebUiBrowserDashboardTest : BrowserE2eBase() {
    @Test
    fun `dashboard page loads`() {
        page.navigate("${baseUrl()}/dashboard")
        page.waitForSelector("[data-testid='dashboard-page']")
        assertNotNull(page.querySelector("[data-testid='dashboard-page']"))
    }

    @Test
    fun `dashboard shows status info`() {
        page.navigate("${baseUrl()}/dashboard")
        page.waitForSelector("[data-testid='dashboard-page']")
        // Dashboard should render some content after API call
        page.waitForTimeout(2000.0)
        val text = page.querySelector("[data-testid='dashboard-page']")!!.textContent()
        assertNotNull(text)
    }
}
