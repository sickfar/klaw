package io.github.klaw.cli.init

import io.github.klaw.cli.util.readFileText
import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DeployConfigTest {
    private val tmpDir = "/tmp/klaw-deploy-config-test-${getpid()}"

    @BeforeTest
    fun setup() {
        platform.posix.mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        platform.posix.unlink("$tmpDir/deploy.conf")
        platform.posix.unlink("$tmpDir/deploy.conf.tmp")
        platform.posix.rmdir(tmpDir)
    }

    @Test
    fun `read mode=native returns NATIVE`() {
        writeTestFile("$tmpDir/deploy.conf", "mode=native\ndocker_tag=latest\n")
        val config = readDeployConf(tmpDir)
        assertEquals(DeployMode.NATIVE, config.mode)
    }

    @Test
    fun `read mode=hybrid returns HYBRID`() {
        writeTestFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=latest\n")
        val config = readDeployConf(tmpDir)
        assertEquals(DeployMode.HYBRID, config.mode)
    }

    @Test
    fun `read mode=docker returns DOCKER`() {
        writeTestFile("$tmpDir/deploy.conf", "mode=docker\ndocker_tag=latest\n")
        val config = readDeployConf(tmpDir)
        assertEquals(DeployMode.DOCKER, config.mode)
    }

    @Test
    fun `read missing file falls back to NATIVE`() {
        val config = readDeployConf("$tmpDir/nonexistent")
        assertEquals(DeployMode.NATIVE, config.mode)
        assertEquals("latest", config.dockerTag)
    }

    @Test
    fun `roundtrip write and read preserves mode and dockerTag`() {
        val original = DeployConfig(DeployMode.HYBRID, "unstable")
        writeDeployConf(tmpDir, original)
        val loaded = readDeployConf(tmpDir)
        assertEquals(DeployMode.HYBRID, loaded.mode)
        assertEquals("unstable", loaded.dockerTag)
    }

    @Test
    fun `unknown mode string falls back to NATIVE`() {
        writeTestFile("$tmpDir/deploy.conf", "mode=unknown_value\ndocker_tag=v1\n")
        val config = readDeployConf(tmpDir)
        assertEquals(DeployMode.NATIVE, config.mode)
        assertEquals("v1", config.dockerTag)
    }

    @Test
    fun `read preserves docker_tag`() {
        writeTestFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=0.4.2\n")
        val config = readDeployConf(tmpDir)
        assertEquals("0.4.2", config.dockerTag)
    }

    @Test
    fun `write creates file with expected content`() {
        writeDeployConf(tmpDir, DeployConfig(DeployMode.DOCKER, "nightly"))
        val content = readFileText("$tmpDir/deploy.conf")
        assertEquals("mode=docker\ndocker_tag=nightly\ninstalled_version=\n", content)
    }

    @Test
    fun `read file with extra whitespace trims correctly`() {
        writeTestFile("$tmpDir/deploy.conf", "  mode = hybrid \n docker_tag = test \n")
        val config = readDeployConf(tmpDir)
        assertEquals(DeployMode.HYBRID, config.mode)
        assertEquals("test", config.dockerTag)
    }

    @Test
    fun `read file with missing docker_tag defaults to latest`() {
        writeTestFile("$tmpDir/deploy.conf", "mode=hybrid\n")
        val config = readDeployConf(tmpDir)
        assertEquals(DeployMode.HYBRID, config.mode)
        assertEquals("latest", config.dockerTag)
    }

    @Test
    fun `installed_version roundtrip preserves value`() {
        val original = DeployConfig(DeployMode.HYBRID, "unstable", "0.2.0")
        writeDeployConf(tmpDir, original)
        val loaded = readDeployConf(tmpDir)
        assertEquals("0.2.0", loaded.installedVersion)
    }

    @Test
    fun `missing installed_version defaults to empty string`() {
        writeTestFile("$tmpDir/deploy.conf", "mode=native\ndocker_tag=latest\n")
        val config = readDeployConf(tmpDir)
        assertEquals("", config.installedVersion)
    }

    @Test
    fun `write preserves all three fields`() {
        val config = DeployConfig(DeployMode.DOCKER, "nightly", "1.0.0")
        writeDeployConf(tmpDir, config)
        val content = readFileText("$tmpDir/deploy.conf")
        assertEquals("mode=docker\ndocker_tag=nightly\ninstalled_version=1.0.0\n", content)
    }

    @Test
    fun `backward compat - old file without installed_version parses correctly`() {
        writeTestFile("$tmpDir/deploy.conf", "mode=hybrid\ndocker_tag=0.4.2\n")
        val config = readDeployConf(tmpDir)
        assertEquals(DeployMode.HYBRID, config.mode)
        assertEquals("0.4.2", config.dockerTag)
        assertEquals("", config.installedVersion)
    }

    @Test
    fun `default DeployConfig has empty installedVersion`() {
        val config = DeployConfig()
        assertEquals("", config.installedVersion)
    }

    private fun writeTestFile(
        path: String,
        content: String,
    ) {
        io.github.klaw.cli.util
            .writeFileText(path, content)
    }
}
