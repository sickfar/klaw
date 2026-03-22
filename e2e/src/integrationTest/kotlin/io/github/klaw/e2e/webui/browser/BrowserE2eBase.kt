package io.github.klaw.e2e.webui.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BrowserE2eBase {
    protected val wireMock = WireMockLlmServer()
    protected lateinit var containers: KlawContainers
    protected lateinit var playwright: Playwright
    protected lateinit var browser: Browser
    protected lateinit var page: Page

    protected fun baseUrl(): String = "http://${containers.gatewayHost}:${containers.gatewayMappedPort}"

    @BeforeAll
    fun startBrowserInfrastructure() {
        wireMock.start()
        val workspaceDir = WorkspaceGenerator.createWorkspace()
        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}",
                        contextBudgetTokens = 5000,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(webuiEnabled = true),
                workspaceDir = workspaceDir,
            )
        containers.start()
        playwright = Playwright.create()
        browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    }

    @AfterAll
    fun stopBrowserInfrastructure() {
        browser.close()
        playwright.close()
        containers.stop()
        wireMock.stop()
    }

    @BeforeEach
    fun newPage() {
        page = browser.newPage()
    }

    @AfterEach
    fun closePage() {
        try {
            val screenshotDir = java.io.File(System.getProperty("java.io.tmpdir"), "playwright-screenshots")
            screenshotDir.mkdirs()
            page.screenshot(
                Page
                    .ScreenshotOptions()
                    .setPath(
                        java.nio.file.Path
                            .of(screenshotDir.path, "last-page.png"),
                    ),
            )
        } catch (_: Exception) {
            // ignore screenshot errors
        }
        page.close()
    }
}
