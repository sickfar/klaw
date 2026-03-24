package io.github.klaw.cli.init

import io.github.klaw.cli.BuildConfig
import io.github.klaw.cli.update.GitHubRelease
import io.github.klaw.cli.update.GitHubReleaseClient
import io.github.klaw.cli.update.GitHubReleaseClientImpl
import kotlinx.coroutines.runBlocking
import kotlin.test.Ignore
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NativeInstallerTest {
    private val noOpReleaseClient =
        object : GitHubReleaseClient {
            override suspend fun fetchLatest() = null

            override suspend fun fetchByTag(tag: String) = null
        }

    private fun buildInstaller(
        commandRunner: (String) -> Int = { 0 },
        commandOutput: (String) -> String? = { null },
    ): NativeInstaller =
        NativeInstaller(
            commandRunner = commandRunner,
            commandOutput = commandOutput,
            printer = {},
            successPrinter = {},
            releaseClient = noOpReleaseClient,
        )

    // --- Java version check ---

    @Test
    fun `checkJavaVersion parses java 21 output`() {
        val installer =
            buildInstaller(
                commandOutput = { "openjdk version \"21.0.1\" 2023-10-17" },
            )
        val version = installer.checkJavaVersion()
        assertTrue(version == 21, "Expected Java 21, got $version")
    }

    @Test
    fun `checkJavaVersion parses java 8 output with 1-dot prefix`() {
        val installer =
            buildInstaller(
                commandOutput = { "java version \"1.8.0_301\"" },
            )
        val version = installer.checkJavaVersion()
        assertTrue(version == 8, "Expected Java 8, got $version")
    }

    @Test
    fun `checkJavaVersion returns null when java not found`() {
        val installer =
            buildInstaller(
                commandOutput = { null },
            )
        val version = installer.checkJavaVersion()
        assertTrue(version == null, "Expected null when java not found, got $version")
    }

    @Test
    fun `checkJavaVersion parses java 17 output`() {
        val installer =
            buildInstaller(
                commandOutput = { "openjdk version \"17.0.5\" 2022-10-18" },
            )
        val version = installer.checkJavaVersion()
        assertTrue(version == 17, "Expected Java 17, got $version")
    }

    // --- Docker availability check ---

    @Test
    fun `isDockerAvailable returns true when docker info succeeds`() {
        val installer = buildInstaller(commandRunner = { 0 })
        assertTrue(installer.isDockerAvailable(), "Expected Docker to be available")
    }

    @Test
    fun `isDockerAvailable returns false when docker info fails`() {
        val installer =
            buildInstaller(
                commandRunner = { if (it.contains("docker info")) 1 else 0 },
            )
        assertTrue(!installer.isDockerAvailable(), "Expected Docker to be unavailable")
    }

    // --- systemd availability check ---

    @Test
    fun `isSystemdAvailable returns true when systemctl succeeds`() {
        val installer = buildInstaller(commandRunner = { 0 })
        assertTrue(installer.isSystemdAvailable(), "Expected systemd to be available")
    }

    @Test
    fun `isSystemdAvailable returns false when systemctl fails`() {
        val installer =
            buildInstaller(
                commandRunner = { if (it.contains("systemctl")) 1 else 0 },
            )
        assertTrue(!installer.isSystemdAvailable(), "Expected systemd to be unavailable")
    }

    // --- ensureJars ---

    @Test
    fun `ensureJars fetches release by own version tag not latest`() {
        var fetchLatestCalled = false
        var fetchByTagArg: String? = null
        val trackingClient =
            object : GitHubReleaseClient {
                override suspend fun fetchLatest(): GitHubRelease? {
                    fetchLatestCalled = true
                    return null
                }

                override suspend fun fetchByTag(tag: String): GitHubRelease? {
                    fetchByTagArg = tag
                    return null
                }
            }
        val installer =
            NativeInstaller(
                commandRunner = { 0 },
                commandOutput = { null },
                printer = {},
                successPrinter = {},
                releaseClient = trackingClient,
                jarDir = "/tmp/klaw-test-jars-${kotlin.random.Random.nextInt()}",
            )
        installer.ensureJars()

        assertFalse(fetchLatestCalled, "ensureJars must not call fetchLatest()")
        assertTrue(
            fetchByTagArg == "v${BuildConfig.VERSION}",
            "Expected fetchByTag(\"v${BuildConfig.VERSION}\"), got fetchByTag(\"$fetchByTagArg\")",
        )
    }

    // --- Integration: real HTTP fetch (run manually, not in CI) ---

    @Ignore
    @Test
    fun `integration - fetchByTag returns release for v0_1_0-rc2 from GitHub`() {
        val client = GitHubReleaseClientImpl()
        val result =
            runBlocking {
                runCatching { client.fetchByTag("v0.1.0-rc2") }
            }
        val exception = result.exceptionOrNull()
        assertTrue(
            exception == null,
            "fetchByTag threw: ${exception?.let { "${it::class.simpleName}: $it" }}",
        )
        val release = result.getOrNull()
        assertNotNull(release, "Expected GitHub release for v0.1.0-rc2, got null — HTTP error?")
        assertTrue(release.tagName == "v0.1.0-rc2", "Expected tag v0.1.0-rc2, got ${release.tagName}")
        assertTrue(
            release.assets.any { it.name.startsWith("klaw-engine") && it.name.endsWith(".jar") },
            "Expected engine JAR asset in release, got: ${release.assets.map { it.name }}",
        )
    }

    // Diagnostic: Ktor CIO on Kotlin/Native does NOT support TLS.
    // Error: "TLS sessions are not supported on Native platform."
    // Fix: switch to Darwin engine (macOS) / Curl engine (Linux).
}
