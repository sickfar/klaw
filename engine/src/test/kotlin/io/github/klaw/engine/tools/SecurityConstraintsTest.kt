package io.github.klaw.engine.tools

import io.github.klaw.common.config.CodeExecutionConfig
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
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
            manager.execute("echo 1", 30)

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
            manager.execute("echo test", 30)

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
            manager.execute("echo 1", 30)

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
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertTrue(call.readOnly, "readOnly rootfs must always be true")
            }
        }

    @Test
    fun `pid host never used`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(call.pidHost, "pidHost must never be true")
            }
        }

    @Test
    fun `workspace directory mounted into container`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker, workspacePath = "/home/klaw/workspace")
            manager.execute("echo 1", 30)

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
            manager.execute("echo test", 30)

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
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains(SANDBOX_WORKSPACE_PATH) },
                    "Should not mount workspace when path is null",
                )
            }
        }

    // --- Volume Validation Tests (Issue #20) ---

    @Test
    fun `etc passwd never mounted`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/etc/passwd:/etc/passwd:ro"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains("/etc/passwd") },
                    "etc/passwd must never be mounted, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `etc shadow never mounted`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/etc/shadow:/data:ro"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains("/etc/shadow") },
                    "etc/shadow must never be mounted, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `etc ssh never mounted`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/etc/ssh:/ssh:ro"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains("/etc/ssh") },
                    "etc/ssh must never be mounted, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `root ssh never mounted`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/root/.ssh:/keys:ro"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains("/root/.ssh") },
                    "root/.ssh must never be mounted, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `root gnupg never mounted`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/root/.gnupg:/gpg:ro"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains("/root/.gnupg") },
                    "root/.gnupg must never be mounted, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `root aws never mounted`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/root/.aws:/aws:ro"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains("/root/.aws") },
                    "root/.aws must never be mounted, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `etc gshadow never mounted`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/etc/gshadow:/data:ro"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains("/etc/gshadow") },
                    "etc/gshadow must never be mounted, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `docker run directory never mounted`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/var/run/docker:/docker:ro"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains("/var/run/docker") },
                    "docker run directory must never be mounted, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `home directory never mounted`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/home:/home:ro"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.startsWith("/home:") || it.startsWith("/home/") },
                    "home directory must never be mounted, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `home user directory never mounted`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/home/user:/user:ro"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains("/home/user") },
                    "home user directory must never be mounted, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `proc never mounted`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/proc:/proc:ro"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains("/proc") },
                    "proc must never be mounted, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `sys never mounted`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/sys:/sys:ro"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(
                    call.volumes.any { it.contains("/sys") },
                    "sys must never be mounted, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `safe volume passes through`() =
        runTest {
            val cfg = config.copy(volumeMounts = listOf("/data/project:/workspace:rw"))
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertTrue(
                    call.volumes.any { it.contains("/data/project") },
                    "Safe volume /data/project should pass through, got: ${call.volumes}",
                )
            }
        }

    @Test
    fun `multiple blocked volumes filtered keeping safe ones`() =
        runTest {
            val cfg =
                config.copy(
                    volumeMounts =
                        listOf(
                            "/etc/passwd:/etc/passwd:ro",
                            "/data/safe:/data:rw",
                            "/root/.ssh:/keys:ro",
                            "/opt/tools:/tools:ro",
                        ),
                )
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertFalse(call.volumes.any { it.contains("/etc/passwd") }, "passwd should be filtered")
                assertFalse(call.volumes.any { it.contains("/root/.ssh") }, "ssh should be filtered")
                assertTrue(call.volumes.any { it.contains("/data/safe") }, "safe volume should pass")
                assertTrue(call.volumes.any { it.contains("/opt/tools") }, "tools volume should pass")
            }
        }

    // --- Non-Root User Tests (Issue #20) ---

    @Test
    fun `container runs as non-root user by default`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertEquals("1000:1000", call.user, "Default user should be 1000:1000")
            }
        }

    @Test
    fun `custom runAsUser from config`() =
        runTest {
            val cfg = config.copy(runAsUser = "500:500")
            val docker = FakeDockerClient()
            val manager = SandboxManager(cfg, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertEquals("500:500", call.user, "Custom user should be propagated")
            }
        }

    // --- Capabilities & Security Opts Tests (Issue #20) ---

    @Test
    fun `capabilities dropped in run options`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertEquals(listOf("ALL"), call.capDrop, "All capabilities should be dropped")
            }
        }

    @Test
    fun `no-new-privileges security opt set`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertTrue(
                    call.securityOpts.contains("no-new-privileges"),
                    "no-new-privileges should be set, got: ${call.securityOpts}",
                )
            }
        }

    @Test
    fun `pids limit set in run options`() =
        runTest {
            val docker = FakeDockerClient()
            val manager = SandboxManager(config, docker)
            manager.execute("echo 1", 30)

            docker.runCalls.forEach { call ->
                assertEquals(64, call.pidsLimit, "pids limit should be 64")
            }
        }
}
