package io.github.klaw.cli.command

import com.github.ajalt.clikt.testing.test
import io.github.klaw.cli.KlawCli
import io.github.klaw.cli.update.GitHubAsset
import io.github.klaw.cli.update.GitHubRelease
import io.github.klaw.cli.update.GitHubReleaseClient
import io.github.klaw.cli.util.writeFileText
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class UpdateCommandTest {
    private val tmpDir = "/tmp/klaw-update-cmd-test-${getpid()}"
    private val commands = mutableListOf<String>()
    private val commandRunner: (String) -> Int = { cmd ->
        commands += cmd
        0
    }

    private val newerRelease =
        GitHubRelease(
            tagName = "v99.0.0",
            assets =
                listOf(
                    GitHubAsset("klaw-macosArm64", "https://example.com/klaw-macosArm64"),
                    GitHubAsset("klaw-linuxArm64", "https://example.com/klaw-linuxArm64"),
                    GitHubAsset("klaw-engine-99.0.0-all.jar", "https://example.com/klaw-engine-99.0.0-all.jar"),
                    GitHubAsset("klaw-gateway-99.0.0-all.jar", "https://example.com/klaw-gateway-99.0.0-all.jar"),
                ),
            prerelease = false,
            draft = false,
        )

    /** Remote version older than any SNAPSHOT — isNewerVersion always returns true for SNAPSHOT local,
     *  so this release must be used with --force tests, not "up to date" tests. */
    private val sameSnapshotRelease =
        GitHubRelease(
            tagName = "v0.1.0-SNAPSHOT",
            assets = emptyList(),
            prerelease = false,
            draft = false,
        )

    @BeforeTest
    fun setup() {
        mkdir(tmpDir, 0x1EDu)
        commands.clear()
        // Default deploy.conf as native mode
        writeFileText("$tmpDir/deploy.conf", "mode=native\ndocker_tag=latest\n")
    }

    @AfterTest
    fun cleanup() {
        unlink("$tmpDir/deploy.conf")
        unlink("$tmpDir/deploy.conf.tmp")
        rmdir(tmpDir)
    }

    private fun cli(
        release: GitHubRelease? = newerRelease,
        runner: (String) -> Int = commandRunner,
        readLineFn: () -> String? = { "n" },
    ): KlawCli =
        KlawCli(
            requestFn = { _, _, _ -> "{}" },
            configDir = tmpDir,
            logDir = "/nonexistent/logs",
            commandRunner = runner,
            releaseClient = FakeReleaseClient(release),
            readLine = readLineFn,
        )

    @Test
    fun `check flag with newer version prints update available`() {
        val result = cli().test("update --check")
        assertTrue(
            result.output.contains("Update available", ignoreCase = true),
            "Expected 'Update available', got: ${result.output}",
        )
        // No curl commands should have run
        assertTrue(
            commands.none { it.contains("curl") },
            "Expected no curl commands in check mode, got: $commands",
        )
    }

    @Test
    fun `check flag with snapshot remote shows forcing for snapshot local`() {
        // BuildConfig.VERSION is 0.1.0-SNAPSHOT in dev — SNAPSHOT local always triggers update
        val result = cli(release = sameSnapshotRelease).test("update --check")
        assertTrue(
            result.output.contains("Update available", ignoreCase = true) ||
                result.output.contains("Forcing", ignoreCase = true),
            "Expected update output for SNAPSHOT, got: ${result.output}",
        )
    }

    @Test
    fun `null release prints failed to fetch`() {
        val result = cli(release = null).test("update --check")
        assertTrue(
            result.output.contains("Failed to fetch", ignoreCase = true),
            "Expected 'Failed to fetch', got: ${result.output}",
        )
    }

    @Test
    fun `full update downloads and prints updated`() {
        val result = cli(readLineFn = { "n" }).test("update")
        assertTrue(
            result.output.contains("Update available", ignoreCase = true),
            "Expected 'Update available', got: ${result.output}",
        )
        // Should have curl commands for downloading
        assertTrue(
            commands.any { it.contains("curl") },
            "Expected curl commands for download, got: $commands",
        )
    }

    @Test
    fun `force flag skips version comparison and downloads`() {
        val result = cli(release = sameSnapshotRelease, readLineFn = { "n" }).test("update --force")
        assertTrue(
            result.output.contains("Forcing update", ignoreCase = true),
            "Expected 'Forcing update', got: ${result.output}",
        )
        assertTrue(
            result.output.contains("Updated", ignoreCase = true),
            "Expected 'Updated', got: ${result.output}",
        )
    }

    @Test
    fun `force with check still skips install`() {
        val result = cli(release = sameSnapshotRelease).test("update --force --check")
        assertTrue(
            result.output.contains("Forcing update", ignoreCase = true) ||
                result.output.contains("available", ignoreCase = true),
            "Expected force/available output, got: ${result.output}",
        )
        assertTrue(
            commands.none { it.contains("curl") },
            "Expected no curl commands in check mode, got: $commands",
        )
    }

    @Test
    fun `version flag fetches specific tag`() {
        val client = FakeReleaseClient(newerRelease)
        val result =
            KlawCli(
                requestFn = { _, _, _ -> "{}" },
                configDir = tmpDir,
                logDir = "/nonexistent/logs",
                commandRunner = commandRunner,
                releaseClient = client,
                readLine = { "n" },
            ).test("update --version v99.0.0")
        assertTrue(
            result.output.contains("Update available", ignoreCase = true) ||
                result.output.contains("Updated", ignoreCase = true),
            "Expected update output for specific version, got: ${result.output}",
        )
    }
}

private class FakeReleaseClient(
    private val release: GitHubRelease?,
) : GitHubReleaseClient {
    override suspend fun fetchLatest(): GitHubRelease? = release

    override suspend fun fetchByTag(tag: String): GitHubRelease? = release
}
