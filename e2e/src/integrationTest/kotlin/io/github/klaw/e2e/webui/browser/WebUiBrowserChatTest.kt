package io.github.klaw.e2e.webui.browser

import com.microsoft.playwright.Page
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder

@Suppress("TooManyFunctions")
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebUiBrowserChatTest : BrowserE2eBase() {
    private fun waitForWsConnected() {
        waitForTestId("connection-dot")
        page.waitForFunction(
            "document.querySelector('[data-testid=\"connection-dot\"]')" +
                "?.classList?.contains('bg-green-500') === true",
            null,
            Page.WaitForFunctionOptions().setTimeout(WS_CONNECT_TIMEOUT),
        )
    }

    @Test
    @Order(1)
    fun `chat page loads with input`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-page")
        assertNotNull(page.querySelector("[data-testid='chat-input']"))
    }

    @Test
    @Order(2)
    fun `chat send button is clickable`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-input")
        page.fill("[data-testid='chat-input']", "Hello agent")

        val sendBtn = page.querySelector("[data-testid='chat-send-button']")
        assertNotNull(sendBtn, "Send button should exist")
        assertTrue(sendBtn!!.isVisible, "Send button should be visible")
    }

    @Test
    @Order(3)
    fun `chat message list renders`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-message-list")
        assertNotNull(page.querySelector("[data-testid='chat-message-list']"))
    }

    @Test
    @Order(4)
    fun `send message shows user message and clears input`() {
        wireMock.stubChatResponse("test response")
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-page")
        waitForWsConnected()

        page.fill("[data-testid='chat-input']", "Hello from test")
        page.click("[data-testid='chat-send-button']")

        waitForTestId("chat-message-user")
        val userMsg = page.querySelector("[data-testid='chat-message-user']")
        assertNotNull(userMsg, "User message should appear")
        assertTrue(userMsg!!.textContent().contains("Hello from test"), "User message should contain sent text")

        val inputVal = page.inputValue("[data-testid='chat-input']")
        assertTrue(inputVal.isEmpty(), "Input should be cleared after send")
    }

    @Test
    @Order(5)
    fun `connection status shows connected`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-page")
        waitForWsConnected()
        val dot = page.querySelector("[data-testid='connection-dot']")
        assertNotNull(dot, "Connection dot should exist")
    }

    @Test
    @Order(6)
    fun `slash command menu appears on slash input`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-input")

        page.locator("[data-testid='chat-input']").pressSequentially("/")

        waitForTestId("slash-command-menu")
        val menu = page.querySelector("[data-testid='slash-command-menu']")
        assertNotNull(menu, "Slash command menu should appear")
        assertTrue(menu!!.isVisible, "Slash command menu should be visible")

        val helpCmd = page.querySelector("[data-testid='slash-cmd-help']")
        assertNotNull(helpCmd, "Should show /help command")
    }

    @Test
    @Order(7)
    fun `slash command menu filters on typing`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-input")

        page.locator("[data-testid='chat-input']").pressSequentially("/mem")

        waitForTestId("slash-command-menu")
        val memoryCmd = page.querySelector("[data-testid='slash-cmd-memory']")
        assertNotNull(memoryCmd, "Should show /memory command")

        val helpCmd = page.querySelector("[data-testid='slash-cmd-help']")
        val statusCmd = page.querySelector("[data-testid='slash-cmd-status']")
        assertFalse(
            helpCmd != null && helpCmd.isVisible,
            "Should not show /help when filtering for /mem",
        )
        assertFalse(
            statusCmd != null && statusCmd.isVisible,
            "Should not show /status when filtering for /mem",
        )
    }

    @Test
    @Order(8)
    fun `full round trip shows assistant response`() {
        wireMock.stubChatResponse("browser-e2e-reply")
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-page")
        waitForWsConnected()

        page.fill("[data-testid='chat-input']", "Hello round trip")
        page.click("[data-testid='chat-send-button']")

        waitForTestId("chat-message-user")

        // Wait for the specific assistant response text (not any historical message)
        page.waitForFunction(
            """Array.from(document.querySelectorAll('[data-testid="chat-message-assistant"]'))
                .some(el => el.textContent.includes('browser-e2e-reply'))""",
            null,
            Page.WaitForFunctionOptions().setTimeout(ASSISTANT_RESPONSE_TIMEOUT),
        )
        val allAssistant = page.querySelectorAll("[data-testid='chat-message-assistant']")
        val matchingMsg = allAssistant.find { it.textContent().contains("browser-e2e-reply") }
        assertNotNull(matchingMsg, "Assistant message with 'browser-e2e-reply' should appear")
    }

    @Test
    @Order(9)
    fun `thinking indicator shows during processing`() {
        wireMock.stubChatResponseWithDelay("delayed-reply", THINKING_DELAY_MS)
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-page")
        waitForWsConnected()

        page.fill("[data-testid='chat-input']", "Trigger thinking")
        page.click("[data-testid='chat-send-button']")

        waitForTestId("thinking-indicator")
        val indicator = page.querySelector("[data-testid='thinking-indicator']")
        assertNotNull(indicator, "Thinking indicator should appear while waiting for response")

        // Wait for the specific delayed response (not historical assistant messages)
        page.waitForFunction(
            """Array.from(document.querySelectorAll('[data-testid="chat-message-assistant"]'))
                .some(el => el.textContent.includes('delayed-reply'))""",
            null,
            Page.WaitForFunctionOptions().setTimeout(ASSISTANT_RESPONSE_TIMEOUT),
        )

        // After the fresh response arrives, thinking indicator should be cleared
        page.waitForFunction(
            """document.querySelector('[data-testid="thinking-indicator"]') === null""",
            null,
            Page.WaitForFunctionOptions().setTimeout(INDICATOR_CLEAR_TIMEOUT),
        )
    }

    @Test
    @Order(10)
    fun `error from LLM shows error in chat`() {
        wireMock.stubChatError(HTTP_INTERNAL_SERVER_ERROR)
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-page")
        waitForWsConnected()

        page.fill("[data-testid='chat-input']", "Trigger error")
        page.click("[data-testid='chat-send-button']")

        // Engine may send error as assistant message or error frame — wait for any assistant message
        page.waitForSelector(
            "[data-testid='chat-message-assistant']",
            Page.WaitForSelectorOptions().setTimeout(ASSISTANT_RESPONSE_TIMEOUT),
        )
        val assistantMsg = page.querySelector("[data-testid='chat-message-assistant']")
        assertNotNull(assistantMsg, "An assistant message should appear after LLM error")
    }

    @Test
    @Order(11)
    fun `websocket reconnects after page navigation`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-page")
        waitForWsConnected()

        page.navigate("${baseUrl()}/dashboard")
        waitForTestId("dashboard-page")

        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-page")
        waitForWsConnected()
    }

    @Test
    @Order(12)
    fun `disconnected send shows only error not user message`() {
        // Inject script that overrides WebSocket before page JS executes
        page.addInitScript(
            """
            window._OriginalWebSocket = window.WebSocket;
            window.WebSocket = function(url, protocols) {
                const ws = new window._OriginalWebSocket(url, protocols);
                setTimeout(() => ws.close(), 50);
                return ws;
            };
            window.WebSocket.OPEN = window._OriginalWebSocket.OPEN;
            window.WebSocket.CLOSED = window._OriginalWebSocket.CLOSED;
            window.WebSocket.CONNECTING = window._OriginalWebSocket.CONNECTING;
            window.WebSocket.CLOSING = window._OriginalWebSocket.CLOSING;
            """,
        )
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-page")

        // Wait for disconnected state (dot should be red)
        page.waitForFunction(
            "document.querySelector('[data-testid=\"connection-dot\"]')" +
                "?.classList?.contains('bg-red-500') === true",
            null,
            Page.WaitForFunctionOptions().setTimeout(SHORT_TIMEOUT),
        )

        page.fill("[data-testid='chat-input']", "Lost message text")
        page.click("[data-testid='chat-send-button']")

        // Wait for error message to appear
        page.waitForFunction(
            """
            (() => {
                const msgs = document.querySelectorAll('[data-testid="chat-message-assistant"]');
                for (const m of msgs) {
                    if (m.textContent.includes('Not connected')) return true;
                }
                return false;
            })()
            """.trimIndent(),
            null,
            Page.WaitForFunctionOptions().setTimeout(SHORT_TIMEOUT),
        )

        // Verify user message with "Lost message text" does NOT appear (Bug B fix)
        val userMessages = page.querySelectorAll("[data-testid='chat-message-user']")
        val hasLostMsg = userMessages.any { it.textContent().contains("Lost message text") }
        assertFalse(hasLostMsg, "User message should NOT appear when send fails (Bug B fix)")
    }

    companion object {
        private const val WS_CONNECT_TIMEOUT = 30_000.0
        private const val ASSISTANT_RESPONSE_TIMEOUT = 30_000.0
        private const val SHORT_TIMEOUT = 10_000.0
        private const val THINKING_DELAY_MS = 2000
        private const val INDICATOR_CLEAR_TIMEOUT = 10_000.0
        private const val HTTP_INTERNAL_SERVER_ERROR = 500
    }
}
