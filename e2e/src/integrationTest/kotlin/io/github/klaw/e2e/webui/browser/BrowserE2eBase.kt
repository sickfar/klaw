package io.github.klaw.e2e.webui.browser

import com.microsoft.playwright.Browser
import com.microsoft.playwright.BrowserType
import com.microsoft.playwright.ConsoleMessage
import com.microsoft.playwright.Page
import com.microsoft.playwright.Playwright
import io.github.klaw.e2e.infra.ConfigGenerator
import io.github.klaw.e2e.infra.KlawContainers
import io.github.klaw.e2e.infra.RestApiClient
import io.github.klaw.e2e.infra.WireMockLlmServer
import io.github.klaw.e2e.infra.WorkspaceGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.TestInstance
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

private val logger = KotlinLogging.logger {}

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
abstract class BrowserE2eBase {
    protected val wireMock = WireMockLlmServer()
    protected lateinit var containers: KlawContainers
    protected lateinit var workspaceDir: File
    protected lateinit var playwright: Playwright
    protected lateinit var browser: Browser
    protected lateinit var page: Page
    protected val consoleErrors: MutableList<String> = CopyOnWriteArrayList()
    protected val restApi: RestApiClient by lazy {
        RestApiClient(containers.gatewayHost, containers.gatewayMappedPort)
    }

    protected fun baseUrl(): String = "http://${containers.gatewayHost}:${containers.gatewayMappedPort}"

    protected fun waitForTestId(
        testId: String,
        timeoutMs: Double = 30_000.0,
    ) {
        page.waitForSelector("[data-testid='$testId']", Page.WaitForSelectorOptions().setTimeout(timeoutMs))
    }

    /** Called after workspace creation and before container start. Override to seed workspace files. */
    protected open fun setupWorkspace(workspace: File) {}

    @BeforeAll
    fun startBrowserInfrastructure() {
        wireMock.start()
        workspaceDir = WorkspaceGenerator.createWorkspace()
        setupWorkspace(workspaceDir)
        containers =
            KlawContainers(
                wireMockPort = wireMock.port,
                engineJson =
                    ConfigGenerator.engineJson(
                        wiremockBaseUrl = "http://host.testcontainers.internal:${wireMock.port}",
                        tokenBudget = 5000,
                    ),
                gatewayJson = ConfigGenerator.gatewayJson(webuiEnabled = true),
                workspaceDir = workspaceDir,
            )
        wireMock.stubChatResponse("default-test-response")
        containers.start()
        playwright = Playwright.create()
        browser = playwright.chromium().launch(BrowserType.LaunchOptions().setHeadless(true))
    }

    @Suppress("TooGenericExceptionCaught")
    @AfterAll
    fun stopBrowserInfrastructure() {
        try {
            restApi.close()
        } catch (_: Exception) {
            // ignore
        }
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
                            .of(screenshotDir.path, "${this::class.simpleName}-${System.currentTimeMillis()}.png"),
                    ),
            )
        } catch (_: Exception) {
            // ignore screenshot errors
        }
        page.close()
    }
}
