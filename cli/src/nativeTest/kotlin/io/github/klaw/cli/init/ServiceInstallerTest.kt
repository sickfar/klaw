package io.github.klaw.cli.init

import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.readFileText
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServiceInstallerTest {
    private val tmpDir = "/private/tmp/klaw-svc-test-${getpid()}"

    @BeforeTest
    fun setup() {
        platform.posix.mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        val entries =
            io.github.klaw.cli.util
                .listDirectory(tmpDir)
        for (entry in entries) {
            platform.posix.unlink("$tmpDir/$entry")
        }
        platform.posix.rmdir(tmpDir)
    }

    @Test
    fun `generates engine unit with EnvironmentFile`() {
        val content = ServiceInstaller.engineSystemdUnit("/usr/local/bin/klaw-engine", "/home/user/.config/klaw/.env")
        assertTrue(content.contains("EnvironmentFile"), "Expected EnvironmentFile in:\n$content")
        assertTrue(content.contains("klaw-engine"), "Expected klaw-engine in:\n$content")
        assertTrue(content.contains("ExecStart=/usr/local/bin/klaw-engine"), "Expected ExecStart in:\n$content")
    }

    @Test
    fun `generates gateway unit with Requires klaw-engine service`() {
        val content = ServiceInstaller.gatewaySystemdUnit("/usr/local/bin/klaw-gateway", "/home/user/.config/klaw/.env")
        assertTrue(content.contains("Requires=klaw-engine.service"), "Expected Requires in:\n$content")
        assertTrue(content.contains("klaw-gateway"), "Expected klaw-gateway in:\n$content")
    }

    @Test
    fun `generates engine launchd plist with label`() {
        val content = ServiceInstaller.engineLaunchdPlist("/usr/local/bin/klaw-engine")
        assertTrue(content.contains("io.github.klaw.engine"), "Expected label in:\n$content")
        assertTrue(content.contains("klaw-engine"), "Expected binary path in:\n$content")
    }

    @Test
    fun `writes systemd units to output directory`() {
        val installer = ServiceInstaller(outputDir = tmpDir, commandRunner = { _ -> })
        installer.writeSystemdUnits("/usr/local/bin/klaw-engine", "/usr/local/bin/klaw-gateway", "/tmp/.env")

        assertTrue(fileExists("$tmpDir/klaw-engine.service"), "klaw-engine.service missing")
        assertTrue(fileExists("$tmpDir/klaw-gateway.service"), "klaw-gateway.service missing")

        val engineContent = readFileText("$tmpDir/klaw-engine.service")
        assertNotNull(engineContent)
        assertTrue(engineContent.contains("EnvironmentFile"), "EnvironmentFile expected in engine unit")
    }
}
