package io.github.klaw.cli.update

import io.github.klaw.cli.InstallPaths
import io.github.klaw.cli.init.DeployMode
import io.github.klaw.cli.init.KlawService
import io.github.klaw.cli.init.ServiceManager
import io.github.klaw.cli.init.chmodExecutable
import io.github.klaw.cli.init.createSymlink
import io.github.klaw.cli.init.mkdirMode755
import io.github.klaw.cli.util.CliLogger
import kotlin.experimental.ExperimentalNativeApi

@Suppress("LongParameterList")
internal class UpdateExecutor(
    private val configDir: String,
    private val mode: DeployMode,
    private val release: GitHubRelease,
    private val printer: (String) -> Unit,
    private val commandRunner: (String) -> Int,
    private val readLine: () -> String?,
    private val commandOutput: (String) -> String? = { null },
    private val binaryPath: String = "${InstallPaths.installDir}/klaw",
    private val jarDir: String = InstallPaths.installDir,
) {
    private val downloader = Downloader(commandRunner)

    fun execute() {
        when (mode) {
            DeployMode.DOCKER -> executeDocker()
            DeployMode.HYBRID -> executeHybrid()
            DeployMode.NATIVE -> executeNative()
        }
    }

    private fun executeDocker() {
        val composeFile = "/app/docker-compose.json"
        printer("Pulling Docker images...")
        commandRunner("docker compose -f '$composeFile' pull")
        if (promptRestart()) {
            commandRunner("docker compose -f '$composeFile' up -d")
        }
    }

    private fun executeHybrid() {
        val composeFile = "$configDir/docker-compose.json"
        printer("Pulling Docker images...")
        commandRunner("docker compose -f '$composeFile' pull")
        downloadCliBinary()
        if (promptRestart()) {
            commandRunner("docker compose -f '$composeFile' up -d")
        }
    }

    private fun executeNative() {
        downloadCliBinary()
        downloadJars()
        if (promptRestart()) {
            restartNativeServices()
        }
    }

    private fun downloadCliBinary() {
        val assetName = cliAssetName()
        val asset = release.assets.firstOrNull { it.name == assetName }
        if (asset == null) {
            printer("CLI binary asset '$assetName' not found in release")
            return
        }
        printer("Downloading CLI binary...")
        if (downloader.downloadAndReplace(asset.browserDownloadUrl, binaryPath)) {
            chmodExecutable(binaryPath)
            mkdirMode755(InstallPaths.symlinkDir)
            createSymlink(binaryPath, "${InstallPaths.symlinkDir}/klaw")
            printer("CLI binary updated")
        } else {
            printer("Failed to download CLI binary")
        }
    }

    private fun downloadJars() {
        val checksumAsset = release.assets.firstOrNull { it.name == ChecksumVerifier.CHECKSUMS_FILENAME }
        val checksums =
            if (checksumAsset != null) {
                ChecksumVerifier.downloadAndParse(
                    checksumAsset.browserDownloadUrl,
                    "$jarDir/${ChecksumVerifier.CHECKSUMS_FILENAME}",
                    downloader,
                )
            } else {
                emptyMap()
            }
        val verifier = ChecksumVerifier(commandOutput)
        downloadJar("engine", checksums, verifier)
        downloadJar("gateway", checksums, verifier)
    }

    private fun downloadJar(
        component: String,
        checksums: Map<String, String>,
        verifier: ChecksumVerifier,
    ) {
        val prefix = jarAssetPrefix(component)
        val asset = release.assets.firstOrNull { it.name.startsWith(prefix) }
        if (asset == null) {
            printer("JAR asset for '$component' not found in release")
            return
        }
        printer("Downloading $component JAR...")
        val destPath = "$jarDir/klaw-$component.jar"
        if (downloader.downloadAndReplace(asset.browserDownloadUrl, destPath)) {
            val expectedHash = checksums[asset.name]
            if (expectedHash != null && !verifier.verify(destPath, expectedHash)) {
                printer("Checksum mismatch for ${asset.name}, removing corrupted file")
                commandRunner("rm -f '$destPath'")
                return
            }
            if (expectedHash == null) {
                CliLogger.debug { "No checksum entry for ${asset.name}, skipping verification" }
            }
            printer("$component JAR updated")
        } else {
            printer("Failed to download $component JAR")
        }
    }

    @OptIn(ExperimentalNativeApi::class)
    private fun restartNativeServices() {
        val serviceManager = ServiceManager(printer, commandRunner, mode)
        serviceManager.restart(KlawService.ENGINE)
        serviceManager.restart(KlawService.GATEWAY)
    }

    private fun promptRestart(): Boolean {
        printer("Restart services now? [y/N]: ")
        val answer = readLine()?.trim() ?: return false
        return answer.equals("y", ignoreCase = true) || answer.equals("yes", ignoreCase = true)
    }
}
