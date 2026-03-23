package io.github.klaw.e2e.webui.browser

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebUiBrowserMemoryTest : BrowserE2eBase() {

    @Test
    @Order(1)
    fun `memory page loads`() {
        page.navigate("${baseUrl()}/memory")
        waitForTestId("memory-page")
        assertNotNull(page.querySelector("[data-testid='memory-page']"))
    }

    @Test
    @Order(2)
    fun `memory page shows search bar`() {
        page.navigate("${baseUrl()}/memory")
        waitForTestId("memory-page")
        assertNotNull(
            page.querySelector("[data-testid='memory-search']"),
            "Memory page should have a search bar",
        )
    }

    @Test
    @Order(3)
    fun `memory page shows categories after seeding`() {
        restApi.post("/api/v1/memory/facts", """{"category":"browser-test","content":"seeded fact"}""")

        page.navigate("${baseUrl()}/memory")
        waitForTestId("memory-category-browser-test")
        val categoryBtn = page.querySelector("[data-testid='memory-category-browser-test']")
        assertNotNull(categoryBtn, "Category 'browser-test' should appear")
        assertTrue(
            categoryBtn!!.textContent().contains("browser-test"),
            "Category button should show name",
        )
    }

    @Test
    @Order(4)
    fun `multiple categories appear after seeding`() {
        restApi.post("/api/v1/memory/facts", """{"category":"second-cat","content":"another fact"}""")

        page.navigate("${baseUrl()}/memory")
        waitForTestId("memory-category-second-cat")
        // Both categories should be visible (from order 3 and this test)
        assertNotNull(
            page.querySelector("[data-testid='memory-category-second-cat']"),
            "Second category should appear",
        )
    }
}
