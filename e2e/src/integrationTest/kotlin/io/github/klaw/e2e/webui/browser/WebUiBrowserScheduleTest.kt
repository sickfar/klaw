package io.github.klaw.e2e.webui.browser

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class WebUiBrowserScheduleTest : BrowserE2eBase() {
    @Test
    fun `schedule page loads`() {
        page.navigate("${baseUrl()}/schedule")
        page.waitForSelector("[data-testid='schedule-page']")
        assertNotNull(page.querySelector("[data-testid='schedule-page']"))
    }
}
