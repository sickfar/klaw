package io.github.klaw.e2e.webui.browser

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebUiBrowserConfigTest : BrowserE2eBase() {
    @Test
    fun `config page loads`() {
        page.navigate("${baseUrl()}/config")
        page.waitForSelector("[data-testid='config-page']")
        assertNotNull(page.querySelector("[data-testid='config-page']"))
    }

    @Test
    fun `config page shows tabs`() {
        page.navigate("${baseUrl()}/config")
        page.waitForSelector("[data-testid='config-page']")
        // Config page should have Engine/Gateway tabs
        page.waitForTimeout(2000.0)
        val text = page.querySelector("[data-testid='config-page']")!!.textContent()
        assertTrue(text.contains("Engine") || text.contains("Gateway"), "Config page should show tabs")
    }
}
