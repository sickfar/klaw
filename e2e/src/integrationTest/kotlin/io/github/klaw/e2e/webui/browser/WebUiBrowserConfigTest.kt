package io.github.klaw.e2e.webui.browser

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebUiBrowserConfigTest : BrowserE2eBase() {
    @Test
    fun `config page loads`() {
        page.navigate("${baseUrl()}/config")
        waitForTestId("config-page")
        assertNotNull(page.querySelector("[data-testid='config-page']"))
    }

    @Test
    fun `config page shows tabs`() {
        page.navigate("${baseUrl()}/config")
        waitForTestId("config-tab-engine")
        val engineTab = page.querySelector("[data-testid='config-tab-engine']")
        val gatewayTab = page.querySelector("[data-testid='config-tab-gateway']")
        assertNotNull(engineTab, "Engine tab should exist")
        assertNotNull(gatewayTab, "Gateway tab should exist")
        assertTrue(engineTab!!.textContent().contains("Engine"), "Engine tab should show text")
        assertTrue(gatewayTab!!.textContent().contains("Gateway"), "Gateway tab should show text")
    }

    @Test
    fun `config tab switching works`() {
        page.navigate("${baseUrl()}/config")
        waitForTestId("config-tab-engine")

        // Engine tab should be active by default
        val engineTabClass = page.getAttribute("[data-testid='config-tab-engine']", "class")
        assertTrue(
            engineTabClass!!.contains("border-primary"),
            "Engine tab should be active by default",
        )

        // Switch to gateway tab
        page.click("[data-testid='config-tab-gateway']")
        val gatewayTabClass = page.getAttribute("[data-testid='config-tab-gateway']", "class")
        assertTrue(
            gatewayTabClass!!.contains("border-primary"),
            "Gateway tab should be active after click",
        )

        // Engine tab should no longer be active
        val engineTabClassAfter = page.getAttribute("[data-testid='config-tab-engine']", "class")
        assertTrue(
            engineTabClassAfter!!.contains("border-transparent"),
            "Engine tab should be inactive after switching",
        )
    }

    @Test
    fun `config page renders schema form`() {
        page.navigate("${baseUrl()}/config")
        waitForTestId("config-schema-form")
        val form = page.querySelector("[data-testid='config-schema-form']")
        assertNotNull(form, "Config schema form should be rendered")
        val formText = form!!.textContent()
        assertTrue(formText.isNotEmpty(), "Config form should have content")
    }

    @Test
    fun `config save triggers API call`() {
        page.navigate("${baseUrl()}/config")
        waitForTestId("config-save-button")
        // Wait for schema form to load before saving
        waitForTestId("config-schema-form")

        page.click("[data-testid='config-save-button']")
        // Wait a moment for the save to complete and check for errors
        page.waitForFunction(
            "!document.querySelector('[data-testid=\"config-save-button\"]')?.classList?.contains('loading')",
        )

        // No error should appear
        assertNull(
            page.querySelector("[data-testid='config-error']"),
            "No error should appear after save",
        )
    }
}
