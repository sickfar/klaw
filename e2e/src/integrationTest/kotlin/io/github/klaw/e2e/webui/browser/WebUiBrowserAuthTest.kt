package io.github.klaw.e2e.webui.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.ConsoleMessage
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

private const val API_TOKEN = "test-secret-token"
private const val SHORT_TIMEOUT = 10_000.0
private const val WS_CONNECT_TIMEOUT = 30_000.0

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class WebUiBrowserAuthTest {
    private val wireMock = WireMockLlmServer()
    private lateinit var containers: KlawContainers
    private lateinit var playwright: Playwright
    private lateinit var browser: Browser
    private lateinit var page: Page
    private val consoleErrors: MutableList<String> = CopyOnWriteArrayList()

    private fun baseUrl(): String = "http://${containers.gatewayHost}:${containers.gatewayMappedPort}"

    private fun waitForTestId(
        testId: String,
        timeoutMs: Double = SHORT_TIMEOUT,
    ) {
        page.waitForSelector("[data-testid='$testId']", Page.WaitForSelectorOptions().setTimeout(timeoutMs))
    }

    @BeforeAll
    fun startInfrastructure() {
        wireMock.start()
        wireMock.stubChatResponse("auth-test-response")
        val workspaceDir = WorkspaceGenerator.createWorkspace()
        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}",
                        contextBudgetTokens = 5000,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(webuiEnabled = true, apiToken = API_TOKEN),
                workspaceDir = workspaceDir,
            )
        containers.start()
        playwright = Playwright.create()
        browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    }

    @Suppress("TooGenericExceptionCaught")
    @AfterAll
    fun stopInfrastructure() {
        try {
            browser.close()
        } catch (_: Exception) {
            // ignore
        }
        try {
            playwright.close()
        } catch (_: Exception) {
            // ignore
        }
        try {
            containers.stop()
        } catch (_: Exception) {
            // ignore
        }
        try {
            wireMock.stop()
        } catch (_: Exception) {
            // ignore
        }
    }

    @BeforeEach
    fun newPage() {
        consoleErrors.clear()
        page = browser.newPage()
        page.onConsoleMessage { msg: ConsoleMessage ->
            if (msg.type() == "error") {
                consoleErrors.add("[${msg.type()}] ${msg.text()}")
            }
        }
    }

    @AfterEach
    fun closePage() {
        try {
            if (consoleErrors.isNotEmpty()) {
                logger.warn { "Browser console errors (${consoleErrors.size}): ${consoleErrors.joinToString("; ")}" }
            }
            val screenshotDir = File(System.getProperty("java.io.tmpdir"), "playwright-screenshots")
            screenshotDir.mkdirs()
            page.screenshot(
                Page
                    .ScreenshotOptions()
                    .setPath(
                        java.nio.file.Path
                            .of(screenshotDir.path, "WebUiBrowserAuthTest-${System.currentTimeMillis()}.png"),
                    ),
            )
        } catch (_: Exception) {
            // ignore screenshot errors
        }
        page.close()
    }

    @Test
    @Order(1)
    fun `auth dialog shown when no token in localStorage`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("auth-gate-overlay")
        waitForTestId("auth-gate-dialog")
        val overlay = page.querySelector("[data-testid='auth-gate-overlay']")
        assertNotNull(overlay, "Auth gate overlay should be visible")
        val dialog = page.querySelector("[data-testid='auth-gate-dialog']")
        assertNotNull(dialog, "Auth gate dialog should be visible")
        // App layout should NOT be visible
        val appLayout = page.querySelector("[data-testid='app-layout']")
        assertNull(appLayout, "App layout should not be rendered when auth required")
    }

    @Test
    @Order(2)
    fun `auth dialog shows error on wrong token`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("auth-gate-dialog")
        page.fill("[data-testid='auth-token-input']", "wrong-token")
        page.click("[data-testid='auth-submit-button']")
        waitForTestId("auth-error")
        val errorEl = page.querySelector("[data-testid='auth-error']")
        assertNotNull(errorEl, "Error message should appear")
        assertTrue(
            errorEl!!.textContent().contains("Invalid token"),
            "Error should say 'Invalid token'",
        )
    }

    @Test
    @Order(3)
    fun `successful login unlocks UI and connects WebSocket`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("auth-gate-dialog")
        page.fill("[data-testid='auth-token-input']", API_TOKEN)
        page.click("[data-testid='auth-submit-button']")
        // Auth gate should disappear, app layout should appear
        waitForTestId("app-layout")
        val overlay = page.querySelector("[data-testid='auth-gate-overlay']")
        assertNull(overlay, "Auth gate overlay should disappear after login")
        val appLayout = page.querySelector("[data-testid='app-layout']")
        assertNotNull(appLayout, "App layout should be visible after login")
        // Verify WebSocket connects (green dot)
        waitForTestId("connection-dot")
        page.waitForFunction(
            "document.querySelector('[data-testid=\"connection-dot\"]')" +
                "?.classList?.contains('bg-green-500') === true",
            null,
            Page.WaitForFunctionOptions().setTimeout(WS_CONNECT_TIMEOUT),
        )
    }

    @Test
    @Order(4)
    fun `token persists across page reload`() {
        // Set token in localStorage first
        page.navigate("${baseUrl()}/chat")
        waitForTestId("auth-gate-dialog")
        page.fill("[data-testid='auth-token-input']", API_TOKEN)
        page.click("[data-testid='auth-submit-button']")
        waitForTestId("app-layout")
        // Reload page
        page.reload()
        // Should NOT show auth dialog again — token is in localStorage
        waitForTestId("app-layout")
        val overlay = page.querySelector("[data-testid='auth-gate-overlay']")
        assertNull(overlay, "Auth gate should not appear after reload with valid token")
    }
}

/**
 * Separate test class with NO apiToken configured — verifies auth gate does not appear.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WebUiBrowserAuthNoTokenTest : BrowserE2eBase() {
    @Test
    fun `no auth dialog when apiToken not configured`() {
        page.navigate("${baseUrl()}/chat")
        waitForTestId("app-layout")
        val overlay = page.querySelector("[data-testid='auth-gate-overlay']")
        assertNull(overlay, "Auth gate should not appear when no apiToken configured")
    }
}
