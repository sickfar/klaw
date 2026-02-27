package io.github.klaw.cli.init

import platform.posix.getpid
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DockerEnvironmentTest {
    private val tmpDir = "/tmp/klaw-docker-env-test-${getpid()}"
    private val fakeDockerEnvPath = "$tmpDir/.dockerenv"

    @BeforeTest
    fun setup() {
        platform.posix.mkdir(tmpDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        platform.posix.unlink(fakeDockerEnvPath)
        platform.posix.rmdir(tmpDir)
    }

    @Test
    fun `isInsideDocker returns false when dockerenv file absent`() {
        assertFalse(
            isInsideDocker("/nonexistent/path/.dockerenv"),
            "Expected false when /.dockerenv does not exist",
        )
    }

    @Test
    fun `isInsideDocker returns true when dockerenv file exists`() {
        platform.posix.creat(fakeDockerEnvPath, 0x1A4u)
        assertTrue(
            isInsideDocker(fakeDockerEnvPath),
            "Expected true when /.dockerenv file exists",
        )
    }
}
