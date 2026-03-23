package io.github.klaw.e2e.webui.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.options.WaitForSelectorState
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebUiBrowserScheduleTest : BrowserE2eBase() {
    @Test
    @Order(1)
    fun `schedule page loads`() {
        page.navigate("${baseUrl()}/schedule")
        waitForTestId("schedule-page")
        assertNotNull(page.querySelector("[data-testid='schedule-page']"))
    }

    @Test
    @Order(2)
    fun `schedule shows empty state when no jobs`() {
        page.navigate("${baseUrl()}/schedule")
        waitForTestId("schedule-page")
        // Wait for loading to finish and empty state to appear
        page.waitForFunction(
            "document.querySelector('[data-testid=\"schedule-page\"]')?.textContent?.includes('No scheduled jobs')",
        )
        val text = page.querySelector("[data-testid='schedule-page']")!!.textContent()
        assertTrue(text.contains("No scheduled jobs"), "Should show empty state, got: $text")
    }

    @Test
    @Order(3)
    fun `create job via API and verify in UI`() {
        restApi.post(
            "/api/v1/schedule/jobs",
            """{"name":"browser-job","cron":"0 0 * * * ?","message":"Test prompt from browser"}""",
        )

        page.navigate("${baseUrl()}/schedule")
        waitForTestId("schedule-job-browser-job")
        val jobCard = page.querySelector("[data-testid='schedule-job-browser-job']")
        assertNotNull(jobCard, "Created job should appear in the list")
        val cardText = jobCard!!.textContent()
        assertTrue(cardText.contains("browser-job"), "Job card should show name")
    }

    @Test
    @Order(4)
    fun `delete job removes it from list`() {
        restApi.post(
            "/api/v1/schedule/jobs",
            """{"name":"delete-me","cron":"0 0 * * * ?","message":"to be deleted"}""",
        )

        page.navigate("${baseUrl()}/schedule")
        waitForTestId("schedule-job-delete-me")
        page.click("[data-testid='schedule-delete-delete-me']")

        page.waitForSelector(
            "[data-testid='schedule-job-delete-me']",
            Page.WaitForSelectorOptions()
                .setState(WaitForSelectorState.HIDDEN)
                .setTimeout(10_000.0),
        )
        assertNull(
            page.querySelector("[data-testid='schedule-job-delete-me']"),
            "Deleted job should no longer be visible",
        )
    }

    @Test
    @Order(5)
    fun `refresh button loads new jobs`() {
        page.navigate("${baseUrl()}/schedule")
        waitForTestId("schedule-page")

        // Seed a job via API while page is open
        restApi.post(
            "/api/v1/schedule/jobs",
            """{"name":"refresh-job","cron":"0 0 * * * ?","message":"seeded after load"}""",
        )

        // Click refresh and wait for the new job to appear
        page.click("[data-testid='schedule-refresh']")
        waitForTestId("schedule-job-refresh-job")
        assertNotNull(
            page.querySelector("[data-testid='schedule-job-refresh-job']"),
            "Newly seeded job should appear after refresh",
        )
    }
}
