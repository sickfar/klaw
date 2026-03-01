package io.github.klaw.engine.tools

import io.github.klaw.common.config.CodeExecutionConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SecurityConstraintsTest {
    private val config =
        CodeExecutionConfig(
            dockerImage = "klaw-sandbox:latest",
            timeout = 30,
            keepAlive = false,
            allowNetwork = true,
            volumeMounts = listOf("/workspace:/workspace:rw"),
        )

    @Test
    fun `privileged flag never in run options`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker)
            manager.execute("python", "print(1)", 30)

            docker.runCalls.forEach { call ->
                assertFalse(call.privileged, "privileged must never be true")
            }
        }

    @Test
    fun `docker socket never mounted`() =
        runTest {
            val configWithSocket =
                config.copy(
                    volumeMounts = listOf("/var/run/docker.sock:/var/run/docker.sock"),
                )
            val docker = FakeDockerClient()
            val manager = SandboxManager(configWithSocket, docker)
            manager.execute("bash", "echo test", 30)

            docker.runCalls.forEach { call ->
                call.volumes.forEach { vol ->
                    assertFalse(
                        vol.contains("docker.sock"),
                        "Docker socket must never be mounted, found: $vol",
                    )
                }
            }
        }

    @Test
    fun `host network never used`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker)
            manager.execute("python", "print(1)", 30)

            docker.runCalls.forEach { call ->
                assertTrue(
                    call.networkMode == "bridge" || call.networkMode == "none",
                    "Network mode must be bridge or none, got: ${call.networkMode}",
                )
            }
        }

    @Test
    fun `read-only rootfs always set`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker)
            manager.execute("python", "print(1)", 30)

            docker.runCalls.forEach { call ->
                assertTrue(call.readOnly, "readOnly rootfs must always be true")
            }
        }

    @Test
    fun `pid host never used`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker)
            manager.execute("python", "print(1)", 30)

            docker.runCalls.forEach { call ->
                assertFalse(call.pidHost, "pidHost must never be true")
            }
        }

    @Test
    fun `workspace directory mounted into container`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker, workspacePath = "/home/klaw/workspace")
            manager.execute("python", "print(1)", 30)

            docker.runCalls.forEach { call ->
                assertTrue(
                    call.volumes.any { it.contains("/home/klaw/workspace") && it.contains(SANDBOX_WORKSPACE_PATH) },
                    "Workspace must be mounted into container, got volumes: ${call.volumes}",
                )
            }
        }

    @Test
    fun `workspace mount is read-write`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker, workspacePath = "/home/klaw/workspace")
            manager.execute("bash", "echo test", 30)

            docker.runCalls.forEach { call ->
                val workspaceVol = call.volumes.first { it.contains(SANDBOX_WORKSPACE_PATH) }
                assertTrue(workspaceVol.endsWith(":rw"), "Workspace mount must be :rw, got: $workspaceVol")
            }
        }

    @Test
    fun `no workspace mount when path is null`() =
        runTest {
            val configNoVols = config.copy(volumeMounts = emptyList())
            val docker = FakeDockerClient()
            val manager = SandboxManager(configNoVols, docker, workspacePath = null)
            manager.execute("python", "print(1)", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains(SANDBOX_WORKSPACE_PATH) },
                    "Should not mount workspace when path is null",
                )
            }
        }
}
