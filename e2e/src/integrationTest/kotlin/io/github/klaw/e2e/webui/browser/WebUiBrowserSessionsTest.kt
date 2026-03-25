package io.github.klaw.e2e.webui.browser

import io.github.klaw.e2e.infra.WebSocketChatClient
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebUiBrowserSessionsTest : BrowserE2eBase() {
    private fun navigateToSessions() {
        page.navigate(baseUrl())
        waitForTestId("app-sidebar")
        page.click("[data-testid='nav-sessions']")
        waitForTestId("sessions-page")
    }

    private fun createSessionViaWebSocket() {
        val wsClient = WebSocketChatClient()
        try {
            wsClient.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
            wsClient.sendAndReceive("Hello from browser e2e test")
        } finally {
            wsClient.close()
        }
    }

    @Test
    @Order(1)
    fun `sessions page loads`() {
        navigateToSessions()
    }

    @Test
    @Order(2)
    fun `sessions page has refresh and cleanup buttons`() {
        navigateToSessions()
        assertNotNull(page.querySelector("[data-testid='sessions-refresh']"), "Refresh button should exist")
        assertNotNull(page.querySelector("[data-testid='sessions-cleanup']"), "Cleanup button should exist")
    }

    @Test
    @Order(3)
    fun `sessions page shows content after API response`() {
        navigateToSessions()
        page.waitForFunction(
            """
            (() => {
                const el = document.querySelector('[data-testid="sessions-page"]');
                if (!el) return false;
                const text = el.textContent || '';
                return text.includes('No active sessions') || text.includes('Chat ID');
            })()
            """.trimIndent(),
        )
        val errorEl = page.querySelector("[data-testid='sessions-error']")
        assertTrue(errorEl == null, "Sessions page should not show an error")
    }

    @Test
    @Order(4)
    fun `session appears after sending chat message`() {
        createSessionViaWebSocket()

        navigateToSessions()
        waitForTestId("sessions-table")

        page.waitForFunction(
            """
            (() => {
                const links = document.querySelectorAll('[data-testid^="session-link-"]');
                return links.length > 0;
            })()
            """.trimIndent(),
        )

        val sessionLinks = page.querySelectorAll("[data-testid^='session-link-']")
        assertTrue(sessionLinks.isNotEmpty(), "At least one session link should appear after chat")
    }

    @Test
    @Order(5)
    fun `sessions table shows chat id and model`() {
        navigateToSessions()
        waitForTestId("sessions-table")

        page.waitForFunction(
            """
            (() => {
                const links = document.querySelectorAll('[data-testid^="session-link-"]');
                return links.length > 0;
            })()
            """.trimIndent(),
        )

        val pageText = page.querySelector("[data-testid='sessions-page']")?.textContent() ?: ""
        assertTrue(pageText.contains("Chat ID"), "Sessions table should contain 'Chat ID' column header")
    }

    @Test
    @Order(6)
    fun `clicking session navigates to chat`() {
        navigateToSessions()
        waitForTestId("sessions-table")

        page.waitForSelector("[data-testid^='session-link-']")
        val firstLink = page.querySelector("[data-testid^='session-link-']")
        assertNotNull(firstLink, "At least one session link should exist")

        firstLink!!.click()
        waitForTestId("chat-page")
        assertTrue(page.url().contains("/chat"), "URL should contain /chat after clicking session")
    }
}
