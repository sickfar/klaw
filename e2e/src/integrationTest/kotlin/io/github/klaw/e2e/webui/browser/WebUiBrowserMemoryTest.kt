package io.github.klaw.e2e.webui.browser

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class WebUiBrowserMemoryTest : BrowserE2eBase() {
    @Test
    fun `memory page loads`() {
        page.navigate("${baseUrl()}/memory")
        page.waitForSelector("[data-testid='memory-page']")
        assertNotNull(page.querySelector("[data-testid='memory-page']"))
    }

    @Test
    fun `memory page shows search bar`() {
        page.navigate("${baseUrl()}/memory")
        page.waitForSelector("[data-testid='memory-page']")
        assertNotNull(
            page.querySelector("[data-testid='memory-search']"),
            "Memory page should have a search bar",
        )
    }
}
