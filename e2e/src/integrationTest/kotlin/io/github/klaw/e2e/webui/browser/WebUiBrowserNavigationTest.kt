package io.github.klaw.e2e.webui.browser

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebUiBrowserNavigationTest : BrowserE2eBase() {
    @Test
    fun `spa loads and shows layout`() {
        page.navigate(baseUrl())
        waitForTestId("app-layout")
        assertNotNull(page.querySelector("[data-testid='app-layout']"))
    }

    @Test
    fun `sidebar navigation items are visible`() {
        page.navigate(baseUrl())
        waitForTestId("app-sidebar")
        assertNotNull(page.querySelector("[data-testid='nav-chat']"))
        assertNotNull(page.querySelector("[data-testid='nav-dashboard']"))
        assertNotNull(page.querySelector("[data-testid='nav-memory']"))
        assertNotNull(page.querySelector("[data-testid='nav-schedule']"))
        assertNotNull(page.querySelector("[data-testid='nav-sessions']"))
        assertNotNull(page.querySelector("[data-testid='nav-skills']"))
        assertNotNull(page.querySelector("[data-testid='nav-config']"))
    }

    @Test
    fun `sidebar navigation to dashboard works`() {
        page.navigate(baseUrl())
        waitForTestId("nav-dashboard")
        page.click("[data-testid='nav-dashboard']")
        waitForTestId("dashboard-page")
        assertTrue(page.url().contains("/dashboard"))
    }

    @Test
    fun `theme toggle exists in DOM`() {
        page.navigate(baseUrl())
        waitForTestId("app-topbar")
        val toggle = page.querySelector("[data-testid='theme-toggle']")
        assertNotNull(toggle, "Theme toggle should exist in DOM")
    }

    @Test
    fun `top bar is visible`() {
        page.navigate(baseUrl())
        waitForTestId("app-topbar")
        assertNotNull(page.querySelector("[data-testid='app-topbar']"))
    }

    @Test
    fun `navigation to all pages works`() {
        page.navigate(baseUrl())
        waitForTestId("app-sidebar")

        val pages =
            listOf(
                "chat" to "chat-page",
                "dashboard" to "dashboard-page",
                "memory" to "memory-page",
                "schedule" to "schedule-page",
                "sessions" to "sessions-page",
                "skills" to "skills-page",
                "config" to "config-page",
            )

        for ((navLabel, pageTestId) in pages) {
            page.click("[data-testid='nav-$navLabel']")
            waitForTestId(pageTestId)
            assertTrue(
                page.url().contains("/$navLabel"),
                "URL should contain '/$navLabel' after clicking nav, got: ${page.url()}",
            )
        }
    }

    @Test
    fun `sidebar toggle hides and shows sidebar`() {
        page.navigate(baseUrl())
        waitForTestId("app-sidebar")

        // Sidebar should be visible initially
        val sidebar = page.querySelector("[data-testid='app-sidebar']")
        assertNotNull(sidebar)
        assertTrue(sidebar!!.isVisible, "Sidebar should be visible initially")

        // Click toggle to hide
        page.click("[data-testid='sidebar-toggle']")
        // v-show sets display:none — wait for it
        page.waitForFunction(
            "!document.querySelector('[data-testid=\"app-sidebar\"]')?.checkVisibility()",
        )

        // Click toggle to show again
        page.click("[data-testid='sidebar-toggle']")
        page.waitForFunction(
            "document.querySelector('[data-testid=\"app-sidebar\"]')?.checkVisibility() === true",
        )
    }

    @Test
    fun `logo shows Klaw text`() {
        page.navigate(baseUrl())
        waitForTestId("app-logo")
        val logoText = page.querySelector("[data-testid='app-logo']")!!.textContent()
        assertEquals("Klaw", logoText.trim(), "Logo should show 'Klaw'")
    }
}
