package io.github.klaw.cli.update

import io.github.klaw.cli.init.DeployMode
import kotlin.test.Test
import kotlin.test.assertTrue

class UpdateExecutorTest {
    private val commands = mutableListOf<String>()
    private val output = mutableListOf<String>()
    private val commandRunner: (String) -> Int = { cmd ->
        commands += cmd
        0
    }
    private val printer: (String) -> Unit = { msg -> output += msg }

    private val testRelease =
        GitHubRelease(
            tagName = "v0.2.0",
            assets =
                listOf(
                    GitHubAsset("klaw-macosArm64", "https://example.com/klaw-macosArm64"),
                    GitHubAsset("klaw-linuxArm64", "https://example.com/klaw-linuxArm64"),
                    GitHubAsset("klaw-engine-0.2.0-all.jar", "https://example.com/klaw-engine-0.2.0-all.jar"),
                    GitHubAsset("klaw-gateway-0.2.0-all.jar", "https://example.com/klaw-gateway-0.2.0-all.jar"),
                ),
            prerelease = false,
            draft = false,
        )

    private fun executor(
        mode: DeployMode = DeployMode.DOCKER,
        release: GitHubRelease = testRelease,
        runner: (String) -> Int = commandRunner,
        readLineFn: () -> String? = { "y" },
        configDir: String = "/tmp/klaw-test",
        binaryPath: String = "/usr/local/bin/klaw",
        jarDir: String = "/usr/local/lib/klaw",
    ): UpdateExecutor =
        UpdateExecutor(
            configDir = configDir,
            mode = mode,
            release = release,
            printer = printer,
            commandRunner = runner,
            readLine = readLineFn,
            binaryPath = binaryPath,
            jarDir = jarDir,
        )

    // --- DOCKER mode ---

    @Test
    fun `docker mode runs docker compose pull`() {
        executor(mode = DeployMode.DOCKER).execute()
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("pull") },
            "Expected docker compose pull, got: $commands",
        )
    }

    @Test
    fun `docker mode on restart y runs docker compose up -d`() {
        executor(mode = DeployMode.DOCKER, readLineFn = { "y" }).execute()
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("up -d") },
            "Expected docker compose up -d, got: $commands",
        )
    }

    @Test
    fun `docker mode uses app compose file`() {
        executor(mode = DeployMode.DOCKER).execute()
        assertTrue(
            commands.any { it.contains("/app/docker-compose.json") },
            "Expected /app/docker-compose.json, got: $commands",
        )
    }

    // --- HYBRID mode ---

    @Test
    fun `hybrid mode runs docker compose pull and downloads CLI binary`() {
        executor(mode = DeployMode.HYBRID).execute()
        assertTrue(
            commands.any { it.contains("docker compose") && it.contains("pull") },
            "Expected docker compose pull, got: $commands",
        )
        assertTrue(
            commands.any { it.contains("curl") && it.contains("klaw-macos") },
            "Expected curl for CLI binary, got: $commands",
        )
    }

    @Test
    fun `hybrid mode uses config dir compose file`() {
        executor(mode = DeployMode.HYBRID, configDir = "/home/user/.config/klaw").execute()
        assertTrue(
            commands.any { it.contains("/home/user/.config/klaw/docker-compose.json") },
            "Expected config dir compose file, got: $commands",
        )
    }

    // --- NATIVE mode ---

    @Test
    fun `native mode downloads CLI binary and JARs`() {
        executor(mode = DeployMode.NATIVE).execute()
        assertTrue(
            commands.any { it.contains("curl") && it.contains("klaw-macos") },
            "Expected curl for CLI binary, got: $commands",
        )
        assertTrue(
            commands.any { it.contains("curl") && it.contains("engine") },
            "Expected curl for engine JAR, got: $commands",
        )
        assertTrue(
            commands.any { it.contains("curl") && it.contains("gateway") },
            "Expected curl for gateway JAR, got: $commands",
        )
    }

    // --- Restart prompt ---

    @Test
    fun `restart prompt n skips restart`() {
        executor(mode = DeployMode.DOCKER, readLineFn = { "n" }).execute()
        val restartCommands = commands.filter { it.contains("up -d") || it.contains("start") }
        // Only pull should have happened, no up -d
        assertTrue(
            restartCommands.isEmpty() || restartCommands.all { it.contains("pull") },
            "Expected no restart commands when user answers 'n', got: $commands",
        )
    }

    @Test
    fun `restart prompt yes case insensitive`() {
        executor(mode = DeployMode.DOCKER, readLineFn = { "YES" }).execute()
        assertTrue(
            commands.any { it.contains("up -d") },
            "Expected restart on YES, got: $commands",
        )
    }

    // --- Missing assets ---

    @Test
    fun `missing CLI asset prints error`() {
        val releaseNoCli =
            GitHubRelease(
                tagName = "v0.2.0",
                assets =
                    listOf(
                        GitHubAsset("klaw-engine-0.2.0-all.jar", "https://example.com/engine.jar"),
                    ),
                prerelease = false,
                draft = false,
            )
        executor(mode = DeployMode.NATIVE, release = releaseNoCli).execute()
        assertTrue(
            output.any { it.contains("CLI binary") && it.contains("not found", ignoreCase = true) },
            "Expected error about missing CLI binary, got: $output",
        )
    }

    @Test
    fun `missing JAR assets in native mode prints error`() {
        val releaseNoJars =
            GitHubRelease(
                tagName = "v0.2.0",
                assets =
                    listOf(
                        GitHubAsset("klaw-macosArm64", "https://example.com/klaw-macosArm64"),
                    ),
                prerelease = false,
                draft = false,
            )
        executor(mode = DeployMode.NATIVE, release = releaseNoJars).execute()
        assertTrue(
            output.any { it.contains("JAR", ignoreCase = true) && it.contains("not found", ignoreCase = true) },
            "Expected error about missing JARs, got: $output",
        )
    }
}
