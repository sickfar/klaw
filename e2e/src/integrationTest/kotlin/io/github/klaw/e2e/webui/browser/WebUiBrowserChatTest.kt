package io.github.klaw.e2e.webui.browser

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@Suppress("TooManyFunctions")
class WebUiBrowserChatTest : BrowserE2eBase() {

    private fun waitForWsConnected() {
        waitForTestId("connection-dot")
        page.waitForFunction(
            "document.querySelector('[data-testid=\"connection-dot\"]')" +
                "?.classList?.contains('bg-green-500') === true",
            null,
            com.microsoft.playwright.Page.WaitForFunctionOptions().setTimeout(30_000.0),
        )
    }

    @Test
    fun `chat page loads with input`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-page")
        assertNotNull(page.querySelector("[data-testid='chat-input']"))
    }

    @Test
    fun `chat send button is clickable`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-input")
        page.fill("[data-testid='chat-input']", "Hello agent")

        val sendBtn = page.querySelector("[data-testid='chat-send-button']")
        assertNotNull(sendBtn, "Send button should exist")
        assertTrue(sendBtn!!.isVisible, "Send button should be visible")
    }

    @Test
    fun `chat message list renders`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-message-list")
        assertNotNull(page.querySelector("[data-testid='chat-message-list']"))
    }

    @Test
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
    fun `connection status shows connected`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("chat-page")
        waitForWsConnected()
        val dot = page.querySelector("[data-testid='connection-dot']")
        assertNotNull(dot, "Connection dot should exist")
    }

    @Test
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
}
