package io.github.klaw.e2e.webui.browser

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebUiBrowserNavigationTest : BrowserE2eBase() {
    @Test
    fun `spa loads and shows layout`() {
        page.navigate(baseUrl())
        page.waitForSelector("[data-testid='app-layout']")
        assertNotNull(page.querySelector("[data-testid='app-layout']"))
    }

    @Test
    fun `sidebar navigation items are visible`() {
        page.navigate(baseUrl())
        page.waitForSelector("[data-testid='app-sidebar']")
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
        page.waitForSelector("[data-testid='nav-dashboard']")
        page.click("[data-testid='nav-dashboard']")
        page.waitForSelector("[data-testid='dashboard-page']")
        assertTrue(page.url().contains("/dashboard"))
    }

    @Test
    fun `theme toggle exists in DOM`() {
        page.navigate(baseUrl())
        page.waitForSelector("[data-testid='app-topbar']")
        // UColorModeButton may render as custom element — check it exists in DOM
        val toggle = page.querySelector("[data-testid='theme-toggle']")
        assertNotNull(toggle, "Theme toggle should exist in DOM")
    }

    @Test
    fun `top bar is visible`() {
        page.navigate(baseUrl())
        page.waitForSelector("[data-testid='app-topbar']")
        assertNotNull(page.querySelector("[data-testid='app-topbar']"))
    }
}
