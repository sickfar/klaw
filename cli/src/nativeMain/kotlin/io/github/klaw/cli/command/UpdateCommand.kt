package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.BuildConfig
import io.github.klaw.cli.init.readDeployConf
import io.github.klaw.cli.init.writeDeployConf
import io.github.klaw.cli.update.GitHubReleaseClient
import io.github.klaw.cli.update.UpdateExecutor
import io.github.klaw.cli.update.isNewerVersion
import kotlinx.coroutines.runBlocking

internal class UpdateCommand(
    private val configDir: String,
    private val releaseClient: GitHubReleaseClient,
    private val commandRunner: (String) -> Int,
    private val readLine: () -> String?,
    private val commandOutput: (String) -> String? = { null },
    private val binaryPath: String = "/usr/local/bin/klaw",
    private val jarDir: String = "/usr/local/lib/klaw",
) : CliktCommand(name = "update") {
    private val check by option("--check", help = "Check for updates without installing").flag()
    private val version by option("--version", help = "Target version tag (e.g. v0.2.0)")

    override fun run() {
        val deployConfig = readDeployConf(configDir)
        val release =
            runBlocking {
                if (version != null) {
                    releaseClient.fetchByTag(version!!)
                } else {
                    releaseClient.fetchLatest()
                }
            }

        if (release == null) {
            echo("Failed to fetch release information")
            return
        }

        if (release.draft) {
            echo("Release ${release.tagName} is a draft, skipping")
            return
        }

        val currentVersion = BuildConfig.VERSION
        if (!isNewerVersion(currentVersion, release.tagName)) {
            echo("Already up to date ($currentVersion)")
            return
        }

        echo("Update available: $currentVersion -> ${release.tagName}")

        if (check) return

        val executor =
            UpdateExecutor(
                configDir = configDir,
                mode = deployConfig.mode,
                release = release,
                printer = ::echo,
                commandRunner = commandRunner,
                readLine = readLine,
                commandOutput = commandOutput,
                binaryPath = binaryPath,
                jarDir = jarDir,
            )
        executor.execute()

        writeDeployConf(configDir, deployConfig.copy(installedVersion = release.tagName))
        echo("Updated to ${release.tagName}")
    }
}
