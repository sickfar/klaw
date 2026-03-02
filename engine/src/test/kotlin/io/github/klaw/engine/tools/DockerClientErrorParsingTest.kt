package io.github.klaw.engine.tools

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DockerClientErrorParsingTest {
    @Test
    fun `throwTypedRunException detects docker daemon unavailable`() {
        assertThrows<SandboxExecutionException.DockerUnavailable> {
            ProcessDockerClient.throwTypedRunException(
                "Cannot connect to the Docker daemon at unix:///var/run/docker.sock",
                "img",
            )
        }
    }

    @Test
    fun `throwTypedRunException detects no such image`() {
        val ex =
            assertThrows<SandboxExecutionException.ImageNotFound> {
                ProcessDockerClient.throwTypedRunException(
                    "Unable to find image 'foo:latest' locally\nNo such image: foo:latest",
                    "foo:latest",
                )
            }
        assertTrue(ex.message!!.contains("foo:latest"))
    }

    @Test
    fun `throwTypedRunException detects pull access denied`() {
        assertThrows<SandboxExecutionException.ImageNotFound> {
            ProcessDockerClient.throwTypedRunException(
                "pull access denied for private-image",
                "private-image",
            )
        }
    }

    @Test
    fun `throwTypedRunException detects permission denied`() {
        assertThrows<SandboxExecutionException.PermissionDenied> {
            ProcessDockerClient.throwTypedRunException(
                "permission denied while trying to connect",
                "img",
            )
        }
    }

    @Test
    fun `throwTypedRunException falls back to container start failure`() {
        val ex =
            assertThrows<SandboxExecutionException.ContainerStartFailure> {
                ProcessDockerClient.throwTypedRunException(
                    "some unknown docker error occurred",
                    "img",
                )
            }
        assertTrue(ex.message!!.contains("some unknown docker error"))
    }

    @Test
    fun `classifyExecResult throws OOM for exit 137 with Killed`() {
        val result = ExecutionResult(stdout = "", stderr = "Killed", exitCode = 137)
        val ex =
            assertThrows<SandboxExecutionException.OutOfMemory> {
                ProcessDockerClient.classifyExecResult(result, "256m")
            }
        assertTrue(ex.message!!.contains("256m"))
    }

    @Test
    fun `classifyExecResult throws OOM for exit 137 with empty stderr`() {
        val result = ExecutionResult(stdout = "", stderr = "", exitCode = 137)
        assertThrows<SandboxExecutionException.OutOfMemory> {
            ProcessDockerClient.classifyExecResult(result, "128m")
        }
    }

    @Test
    fun `classifyExecResult does not throw OOM for timed out result`() {
        val result = ExecutionResult(stdout = "", stderr = "", exitCode = 137, timedOut = true)
        val classified = ProcessDockerClient.classifyExecResult(result, "256m")
        assertTrue(classified.timedOut, "Should pass through timed out results")
    }

    @Test
    fun `classifyExecResult throws permission denied`() {
        val result =
            ExecutionResult(
                stdout = "",
                stderr = "bash: /root: Permission denied",
                exitCode = 1,
            )
        assertThrows<SandboxExecutionException.PermissionDenied> {
            ProcessDockerClient.classifyExecResult(result, "256m")
        }
    }

    @Test
    fun `classifyExecResult does not throw OOM for exit 137 with non-OOM stderr`() {
        val result = ExecutionResult(stdout = "", stderr = "some other error", exitCode = 137)
        val classified = ProcessDockerClient.classifyExecResult(result, "256m")
        assertTrue(classified.exitCode == 137, "Should pass through non-OOM 137")
    }

    @Test
    fun `classifyExecResult passes through normal results`() {
        val result = ExecutionResult(stdout = "hello", stderr = "", exitCode = 0)
        val classified = ProcessDockerClient.classifyExecResult(result, "256m")
        assertTrue(classified.stdout == "hello")
    }

    @Test
    fun `error detail is truncated to 200 chars`() {
        val longError = "a".repeat(300)
        val ex =
            assertThrows<SandboxExecutionException.ContainerStartFailure> {
                ProcessDockerClient.throwTypedRunException(longError, "img")
            }
        assertTrue(
            ex.message!!.length < 300,
            "Error detail should be truncated",
        )
    }
}
