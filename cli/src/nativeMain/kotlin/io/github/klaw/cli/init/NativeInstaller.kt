package io.github.klaw.cli.init

import io.github.klaw.cli.BuildConfig
import io.github.klaw.cli.ui.AnsiColors
import io.github.klaw.cli.update.Downloader
import io.github.klaw.cli.update.GitHubReleaseClient
import io.github.klaw.cli.update.isNewerVersion
import io.github.klaw.cli.update.jarAssetPrefix
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.listDirectory
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.paths.KlawPaths
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking

private const val MIN_JAVA_VERSION = 21

/**
 * Handles native-mode installation tasks: Java version check, Docker detection,
 * systemd detection, JAR download, and wrapper script creation.
 */
@OptIn(ExperimentalForeignApi::class)
internal class NativeInstaller(
    private val commandRunner: (String) -> Int,
    private val commandOutput: (String) -> String?,
    private val printer: (String) -> Unit,
    private val successPrinter: (String) -> Unit,
    private val releaseClient: GitHubReleaseClient,
    private val jarDir: String = "${KlawPaths.data}/bin",
    private val binDir: String = "${platform.posix.getenv("HOME")?.toKString() ?: "~"}/.local/bin",
) {
    /**
     * Parses the Java major version from `java -version` output.
     * Returns the major version number (e.g. 21) or null if Java is not found or output is unparseable.
     */
    fun checkJavaVersion(): Int? {
        val output = commandOutput("java -version 2>&1") ?: return null
        // java -version outputs: openjdk version "21.0.1" or java version "1.8.0_301"
        val versionRegex = Regex("""version\s+"(\d+)(?:\.(\d+))?""")
        val match = versionRegex.find(output) ?: return null
        val major = match.groupValues[1].toIntOrNull() ?: return null
        // Java 8 and earlier: version "1.8.x" → major is second part
        return if (major == 1) match.groupValues[2].toIntOrNull() else major
    }

    /** Checks if Docker is available on the system. */
    fun isDockerAvailable(): Boolean = commandRunner("docker info > /dev/null 2>&1") == 0

    /** Checks if systemd user services are available (Linux only). */
    fun isSystemdAvailable(): Boolean = commandRunner("systemctl --user --version > /dev/null 2>&1") == 0

    /**
     * Prints a Java pre-flight check result.
     * Warns if Java is missing or below [MIN_JAVA_VERSION].
     */
    fun printJavaCheck() {
        val javaVersion = checkJavaVersion()
        if (javaVersion == null) {
            printer(
                "${AnsiColors.YELLOW}⚠ Java not found. Engine and Gateway require Java $MIN_JAVA_VERSION+. " +
                    "Install it before starting services.${AnsiColors.RESET}",
            )
        } else if (javaVersion < MIN_JAVA_VERSION) {
            printer(
                "${AnsiColors.YELLOW}⚠ Java $javaVersion found, but $MIN_JAVA_VERSION+ is required. " +
                    "Upgrade before starting services.${AnsiColors.RESET}",
            )
        } else {
            successPrinter("Java $javaVersion detected")
        }
    }

    /**
     * Ensures engine and gateway JARs are present in [jarDir], downloading from GitHub if needed.
     * If JARs exist and the local CLI version is up to date, skips download.
     */
    fun ensureJars() {
        mkdirMode755(jarDir)
        val files = listDirectory(jarDir)
        val hasEngine = files.any { it.startsWith("klaw-engine") && it.endsWith(".jar") }
        val hasGateway = files.any { it.startsWith("klaw-gateway") && it.endsWith(".jar") }

        val release = fetchLatestRelease()

        if (hasEngine && hasGateway) {
            CliLogger.debug { "JARs found in $jarDir, checking for updates" }
            if (release == null || !isNewerVersion(BuildConfig.VERSION, release.tagName)) {
                CliLogger.debug { "JARs are up to date" }
                successPrinter("Engine and Gateway JARs are up to date")
                return
            }
            printer("Newer version available (${release.tagName}), downloading JARs...")
        } else {
            printer("Downloading Engine and Gateway JARs...")
            if (release == null) {
                printer(
                    "${AnsiColors.YELLOW}⚠ Could not fetch release info. " +
                        "Run 'klaw update' later to download JARs.${AnsiColors.RESET}",
                )
                return
            }
        }
        downloadJars(release.assets.associate { it.name to it.browserDownloadUrl })
    }

    private fun fetchLatestRelease() =
        runBlocking {
            runCatching { releaseClient.fetchLatest() }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    CliLogger.warn { "failed to fetch release: ${e::class.simpleName}" }
                    null
                }
        }

    private fun downloadJars(assets: Map<String, String>) {
        val downloader = Downloader(commandRunner)
        var allSucceeded = true
        for (component in listOf("engine", "gateway")) {
            val prefix = jarAssetPrefix(component)
            val entry = assets.entries.firstOrNull { it.key.startsWith(prefix) }
            if (entry == null) {
                printer("${AnsiColors.YELLOW}⚠ JAR asset for '$component' not found in release${AnsiColors.RESET}")
                allSucceeded = false
                continue
            }
            val destPath = "$jarDir/klaw-$component.jar"
            if (downloader.downloadAndReplace(entry.value, destPath)) {
                CliLogger.debug { "$component JAR downloaded to $destPath" }
            } else {
                printer("${AnsiColors.YELLOW}⚠ Failed to download $component JAR${AnsiColors.RESET}")
                allSucceeded = false
            }
        }
        if (allSucceeded) {
            successPrinter("Engine and Gateway JARs downloaded")
        } else {
            printer(
                "${AnsiColors.YELLOW}⚠ Some JARs failed to download. " +
                    "Run 'klaw update' to retry.${AnsiColors.RESET}",
            )
        }
    }

    /**
     * Creates wrapper scripts for engine and gateway in [binDir] if they don't exist.
     */
    fun createWrapperScripts() {
        mkdirMode755(binDir)
        val engineWrapper = "$binDir/klaw-engine"
        if (!fileExists(engineWrapper)) {
            writeFileText(
                engineWrapper,
                "#!/usr/bin/env bash\n" +
                    "exec java -Xms64m -Xmx512m " +
                    "-jar \"$jarDir/klaw-engine.jar\" \"\$@\"\n",
            )
            chmodExecutable(engineWrapper)
            CliLogger.debug { "created engine wrapper at $engineWrapper" }
        }
        val gatewayWrapper = "$binDir/klaw-gateway"
        if (!fileExists(gatewayWrapper)) {
            writeFileText(
                gatewayWrapper,
                "#!/usr/bin/env bash\n" +
                    "exec java -Xms32m -Xmx128m " +
                    "-jar \"$jarDir/klaw-gateway.jar\" \"\$@\"\n",
            )
            chmodExecutable(gatewayWrapper)
            CliLogger.debug { "created gateway wrapper at $gatewayWrapper" }
        }
        successPrinter("Wrapper scripts ready")
    }
}
