package io.github.klaw.cli.init

import io.github.klaw.cli.BuildConfig
import io.github.klaw.cli.InstallPaths
import io.github.klaw.cli.ui.AnsiColors
import io.github.klaw.cli.update.ChecksumVerifier
import io.github.klaw.cli.update.Downloader
import io.github.klaw.cli.update.GitHubRelease
import io.github.klaw.cli.update.GitHubReleaseClient
import io.github.klaw.cli.update.jarAssetPrefix
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.listDirectory
import io.github.klaw.cli.util.writeFileText
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
    private val jarDir: String = InstallPaths.installDir,
    private val binDir: String = InstallPaths.installDir,
) {
    private var cachedRelease: GitHubRelease? = null

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
     * Checks if JARs need downloading and pre-fetches release metadata.
     * Returns true if JARs are present or release metadata was fetched successfully.
     * Returns false if JARs are missing and release fetch failed — caller should abort.
     */
    fun prefetchRelease(): Boolean {
        mkdirMode755(jarDir)
        val files = listDirectory(jarDir)
        val hasEngine = files.any { it.startsWith("klaw-engine") && it.endsWith(".jar") }
        val hasGateway = files.any { it.startsWith("klaw-gateway") && it.endsWith(".jar") }
        CliLogger.debug { "prefetchRelease: jarDir=$jarDir hasEngine=$hasEngine hasGateway=$hasGateway" }

        if (hasEngine && hasGateway) {
            CliLogger.debug { "JARs found in $jarDir, version pinned to ${BuildConfig.VERSION}" }
            return true
        }

        CliLogger.debug { "fetching release for tag v${BuildConfig.VERSION}" }
        cachedRelease = fetchOwnRelease()
        val tag = cachedRelease?.tagName
        CliLogger.debug { "prefetchRelease result: ${if (tag != null) "found ($tag)" else "null"}" }
        return cachedRelease != null
    }

    /**
     * Downloads engine and gateway JARs. Call [prefetchRelease] first.
     */
    fun ensureJars() {
        mkdirMode755(jarDir)
        val files = listDirectory(jarDir)
        val hasEngine = files.any { it.startsWith("klaw-engine") && it.endsWith(".jar") }
        val hasGateway = files.any { it.startsWith("klaw-gateway") && it.endsWith(".jar") }

        if (hasEngine && hasGateway) {
            successPrinter("Engine and Gateway JARs are up to date")
            return
        }

        val release = cachedRelease ?: fetchOwnRelease()
        printer("Downloading Engine and Gateway JARs...")
        if (release == null) {
            printer(
                "${AnsiColors.YELLOW}⚠ Could not fetch release info. " +
                    "Run 'klaw update' later to download JARs.${AnsiColors.RESET}",
            )
            return
        }
        downloadJars(release.assets.associate { it.name to it.browserDownloadUrl })
    }

    private fun fetchOwnRelease() =
        runBlocking {
            runCatching { releaseClient.fetchByTag("v${BuildConfig.VERSION}") }
                .getOrElse { e ->
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    CliLogger.warn { "failed to fetch release: ${e::class.simpleName}" }
                    null
                }
        }

    private fun downloadJars(assets: Map<String, String>) {
        val downloader = Downloader(commandRunner)
        val checksumUrl = assets[ChecksumVerifier.CHECKSUMS_FILENAME]
        val checksums =
            if (checksumUrl != null) {
                ChecksumVerifier.downloadAndParse(
                    checksumUrl,
                    "$jarDir/${ChecksumVerifier.CHECKSUMS_FILENAME}",
                    downloader,
                )
            } else {
                emptyMap()
            }
        val verifier = ChecksumVerifier(commandOutput)
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
                if (!verifyDownload(verifier, checksums, entry.key, destPath)) {
                    allSucceeded = false
                }
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

    private fun verifyDownload(
        verifier: ChecksumVerifier,
        checksums: Map<String, String>,
        assetName: String,
        filePath: String,
    ): Boolean {
        val expectedHash = checksums[assetName]
        if (expectedHash == null) {
            CliLogger.debug { "No checksum entry for $assetName, skipping verification" }
            return true
        }
        if (verifier.verify(filePath, expectedHash)) {
            CliLogger.debug { "Checksum verified for $assetName" }
            return true
        }
        printer("${AnsiColors.YELLOW}⚠ Checksum mismatch for $assetName${AnsiColors.RESET}")
        commandRunner("rm -f '$filePath'")
        return false
    }

    /**
     * Creates wrapper scripts for engine and gateway in [binDir] if they don't exist.
     */
    fun createWrapperScripts() {
        mkdirMode755(binDir)
        val javaPath = resolveJavaPath()
        CliLogger.debug { "resolved java path: $javaPath" }
        val engineWrapper = "$binDir/klaw-engine"
        if (!fileExists(engineWrapper)) {
            writeFileText(
                engineWrapper,
                "#!/usr/bin/env bash\n" +
                    "exec $javaPath -Xms64m -Xmx512m " +
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
                    "exec $javaPath -Xms32m -Xmx128m " +
                    "-jar \"$jarDir/klaw-gateway.jar\" \"\$@\"\n",
            )
            chmodExecutable(gatewayWrapper)
            CliLogger.debug { "created gateway wrapper at $gatewayWrapper" }
        }
        mkdirMode755(InstallPaths.symlinkDir)
        createSymlink(engineWrapper, "${InstallPaths.symlinkDir}/klaw-engine")
        createSymlink(gatewayWrapper, "${InstallPaths.symlinkDir}/klaw-gateway")
        successPrinter("Wrapper scripts ready")
    }

    @OptIn(ExperimentalForeignApi::class)
    private fun resolveJavaPath(): String {
        val resolved = commandOutput("command -v java 2>/dev/null")?.trim()
        if (!resolved.isNullOrBlank()) return resolved
        val javaHome = platform.posix.getenv("JAVA_HOME")?.toKString()
        if (!javaHome.isNullOrBlank()) return "$javaHome/bin/java"
        return "java"
    }
}
