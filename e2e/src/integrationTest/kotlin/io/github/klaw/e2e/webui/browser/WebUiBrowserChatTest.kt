package io.github.klaw.e2e.webui.browser

import com.microsoft.playwright.Page
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebUiBrowserChatTest : BrowserE2eBase() {
    @Test
    fun `chat page loads with input`() {
        page.navigate("${baseUrl()}/chat")
        page.waitForSelector("[data-testid='chat-page']")
        assertNotNull(page.querySelector("[data-testid='chat-page']"))
        assertNotNull(page.querySelector("[data-testid='chat-input']"))
    }

    @Test
    fun `chat send button is clickable`() {
        page.navigate("${baseUrl()}/chat")
        page.waitForSelector("[data-testid='chat-input']")
        page.fill("[data-testid='chat-input']", "Hello agent")

        val sendBtn = page.querySelector("[data-testid='chat-send-button']")
        assertNotNull(sendBtn, "Send button should exist")
        assertTrue(sendBtn!!.isVisible, "Send button should be visible")
    }

    @Test
    fun `chat message list renders`() {
        page.navigate("${baseUrl()}/chat")
        page.waitForSelector("[data-testid='chat-message-list']")
        assertNotNull(page.querySelector("[data-testid='chat-message-list']"))
    }
}
