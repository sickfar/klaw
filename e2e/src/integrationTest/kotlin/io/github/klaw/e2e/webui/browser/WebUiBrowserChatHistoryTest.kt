package io.github.klaw.e2e.webui.browser

import com.microsoft.playwright.Page
import io.github.klaw.e2e.infra.WebSocketChatClient
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebUiBrowserChatHistoryTest : BrowserE2eBase() {
    private fun createSessionViaWebSocket(): Pair<String, String> {
        val userMsg = "history-test-user-${System.currentTimeMillis()}"
        val assistantMsg = "history-test-reply-${System.currentTimeMillis()}"
        wireMock.stubChatResponse(assistantMsg)
        val wsClient = WebSocketChatClient()
        try {
            wsClient.connectAsync(containers.gatewayHost, containers.gatewayMappedPort)
            wsClient.sendAndReceive(userMsg)
        } finally {
            wsClient.close()
        }
        return userMsg to assistantMsg
    }

    private fun waitForHistoryMessage(content: String) {
        page.waitForFunction(
            """
            (() => {
                const msgs = document.querySelectorAll('[data-testid^="chat-message-"]');
                for (const m of msgs) {
                    if (m.textContent.includes('$content')) return true;
                }
                return false;
            })()
            """.trimIndent(),
            null,
            Page.WaitForFunctionOptions().setTimeout(HISTORY_LOAD_TIMEOUT),
        )
    }

    @Test
    @Order(1)
    fun `history loads with session query parameter`() {
        val (userMsg, assistantMsg) = createSessionViaWebSocket()

        page.navigate("${baseUrl()}/chat?session=local_ws_default")
        waitForTestId("chat-page")

        waitForHistoryMessage(userMsg)

        val messages = page.querySelectorAll("[data-testid^='chat-message-']")
        val allText = messages.joinToString(" ") { it.textContent() }
        assertTrue(allText.contains(userMsg), "History should contain user message: $userMsg")
        assertTrue(allText.contains(assistantMsg), "History should contain assistant message: $assistantMsg")
    }

    @Test
    @Order(2)
    fun `history loads on chat page without query param (default session)`() {
        val (userMsg, assistantMsg) = createSessionViaWebSocket()

        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-page")

        waitForHistoryMessage(userMsg)

        val messages = page.querySelectorAll("[data-testid^='chat-message-']")
        val allText = messages.joinToString(" ") { it.textContent() }
        assertTrue(allText.contains(userMsg), "History should contain user message: $userMsg")
        assertTrue(allText.contains(assistantMsg), "History should contain assistant message: $assistantMsg")
    }

    @Test
    @Order(3)
    fun `clicking session on sessions page loads history`() {
        val (userMsg, assistantMsg) = createSessionViaWebSocket()

        page.navigate(baseUrl())
        waitForTestId("app-sidebar")
        page.click("[data-testid='nav-sessions']")
        waitForTestId("sessions-page")

        page.waitForSelector("[data-testid^='session-link-']")
        page.click("[data-testid^='session-link-']")
        waitForTestId("chat-page")

        waitForHistoryMessage(userMsg)

        val messages = page.querySelectorAll("[data-testid^='chat-message-']")
        val allText = messages.joinToString(" ") { it.textContent() }
        assertTrue(allText.contains(userMsg), "History should contain user message: $userMsg")
        assertTrue(allText.contains(assistantMsg), "History should contain assistant message: $assistantMsg")
    }

    companion object {
        private const val HISTORY_LOAD_TIMEOUT = 30_000.0
    }
}
