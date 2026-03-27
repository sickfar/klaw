package io.github.klaw.engine.tools

import io.github.klaw.common.config.CodeExecutionConfig
import io.github.klaw.engine.BuildConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.io.File
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

const val SANDBOX_WORKSPACE_PATH = "/workspace"
private const val MAX_OUTPUT_CHARS = 10_000
private const val PID_FILE_NAME = "sandbox.pid"
private const val CLEANUP_TIMEOUT_SECONDS = 5
private const val PIDS_LIMIT = 64

class SandboxManager(
    private val config: CodeExecutionConfig,
    private val docker: DockerClient,
    private val workspacePath: String? = null,
    private val hostWorkspacePath: String? = null,
    private val stateDir: String? = null,
) {
    @Volatile private var containerId: String? = null

    @Volatile private var executionCount = 0

    @Volatile private var orphanCleanupDone = false
    private var lastExecutionTime: Instant = Instant.DISTANT_PAST

    val isContainerActive: Boolean get() = containerId != null
    val currentExecutionCount: Int get() = executionCount
    val isKeepAlive: Boolean get() = config.keepAlive

    suspend fun execute(
        code: String,
        timeout: Int,
    ): SandboxExecOutput =
        if (config.keepAlive) {
            executeKeepAlive(code, timeout)
        } else {
            executeOneshot(code, timeout)
        }

    private suspend fun executeKeepAlive(
        code: String,
        timeout: Int,
    ): SandboxExecOutput {
        val id = getOrCreateContainer()
        executionCount++
        lastExecutionTime = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        clearTmp(id)
        val cmd = listOf("bash", "-c", code)
        val raw = docker.exec(id, cmd, timeout)
        val result = ProcessDockerClient.classifyExecResult(raw, config.maxMemory)
        logger.trace { "Keep-alive exec completed: exitCode=${result.exitCode}, timedOut=${result.timedOut}" }
        return SandboxExecOutput(
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
            timedOut = result.timedOut,
        )
    }

    private suspend fun executeOneshot(
        code: String,
        timeout: Int,
    ): SandboxExecOutput {
        val name = "klaw-sandbox-${UUID.randomUUID()}"
        val cmd = listOf("timeout", timeout.toString(), "bash", "-c", code)
        val options = buildRunOptions(name, remove = true, command = cmd)
        logger.trace { "Oneshot run: name=$name" }
        val result = docker.run(options)
        return SandboxExecOutput(
            stdout = result.stdout,
            stderr = result.stderr,
            exitCode = result.exitCode,
        )
    }

    private suspend fun getOrCreateContainer(): String {
        cleanupOrphanContainers()
        val current = containerId ?: recoverPersistedContainer()
        val isOrphaned = containerId == null && current != null
        val shouldRecreate =
            current == null ||
                isOrphaned ||
                executionCount >= config.keepAliveMaxExecutions ||
                isIdleTimeoutExpired()

        if (shouldRecreate) {
            if (current != null) {
                destroyContainer(current)
            }
            val name = "klaw-sandbox-${UUID.randomUUID()}"
            val options = buildRunOptions(name, detach = true)
            val result = docker.run(options)
            val id = result.stdout
            containerId = id
            executionCount = 0
            lastExecutionTime = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            persistContainerId(id)
            logger.debug { "Created keep-alive container" }
            return id
        }
        return current
    }

    private suspend fun cleanupOrphanContainers() {
        if (orphanCleanupDone) return
        orphanCleanupDone = true
        try {
            val containers = docker.listContainers("klaw-sandbox-")
            val persisted = recoverPersistedContainer()
            for (container in containers) {
                if (container != persisted) {
                    removeOrphanContainer(container)
                }
            }
        } catch (_: Exception) {
            logger.trace { "Orphan scan skipped due to error" }
        }
    }

    private suspend fun removeOrphanContainer(container: String) {
        try {
            docker.stop(container)
            docker.rm(container)
            logger.debug { "Cleaned up orphaned sandbox container" }
        } catch (_: Exception) {
            logger.trace { "Orphan cleanup completed with warnings" }
        }
    }

    private suspend fun clearTmp(containerId: String) {
        try {
            docker.exec(containerId, listOf("sh", "-c", "find /tmp -mindepth 1 -delete"), CLEANUP_TIMEOUT_SECONDS)
        } catch (_: Exception) {
            logger.trace { "tmp cleanup completed with warnings" }
        }
    }

    private fun recoverPersistedContainer(): String? {
        val dir = stateDir ?: return null
        val pidFile = File(dir, PID_FILE_NAME)
        if (!pidFile.exists()) return null
        val id = pidFile.readText().trim()
        if (id.isEmpty()) return null
        logger.debug { "Recovered orphaned sandbox container from state file" }
        return id
    }

    private fun persistContainerId(id: String) {
        val dir = stateDir ?: return
        File(dir, PID_FILE_NAME).writeText(id)
    }

    private fun deletePersistedContainerId() {
        val dir = stateDir ?: return
        val pidFile = File(dir, PID_FILE_NAME)
        pidFile.delete()
    }

    private fun isIdleTimeoutExpired(): Boolean {
        if (lastExecutionTime == Instant.DISTANT_PAST) return false
        val now = Instant.fromEpochMilliseconds(System.currentTimeMillis())
        val elapsed = now - lastExecutionTime
        return elapsed > config.keepAliveIdleTimeoutMin.minutes
    }

    suspend fun shutdown() {
        val id = containerId ?: return
        destroyContainer(id)
        containerId = null
        deletePersistedContainerId()
        logger.debug { "SandboxManager shutdown complete" }
    }

    private suspend fun destroyContainer(id: String) {
        try {
            docker.stop(id)
            docker.rm(id)
        } catch (_: Exception) {
            logger.trace { "Container cleanup completed with warnings" }
        }
        deletePersistedContainerId()
    }

    private fun buildRunOptions(
        name: String,
        detach: Boolean = false,
        remove: Boolean = false,
        command: List<String> = listOf("sleep", "infinity"),
    ): DockerRunOptions {
        val safeVolumes = filterVolumes(config.volumeMounts).toMutableList()
        if (workspacePath != null) {
            val volumeSource = hostWorkspacePath ?: workspacePath
            safeVolumes.add("$volumeSource:$SANDBOX_WORKSPACE_PATH:rw")
        }
        return DockerRunOptions(
            image = resolveDockerImage(),
            name = name,
            memoryLimit = config.maxMemory,
            cpuLimit = config.maxCpus,
            readOnly = true,
            networkMode = if (config.allowNetwork) "bridge" else "none",
            tmpfs = mapOf("/tmp" to "rw,size=64m"),
            volumes = safeVolumes,
            command = command,
            entrypoint = "",
            detach = detach,
            remove = remove,
            user = config.runAsUser,
            capDrop = listOf("ALL"),
            securityOpts = listOf("no-new-privileges"),
            pidsLimit = PIDS_LIMIT,
        )
    }

    private fun resolveDockerImage(): String {
        val configured = config.dockerImage
        if (!configured.endsWith(":latest")) return configured
        val version = BuildConfig.VERSION
        if (version.endsWith("-SNAPSHOT")) return configured
        return configured.replace(":latest", ":$version")
    }

    companion object {
        private val BLOCKED_HOST_PATHS =
            listOf(
                "/etc/passwd",
                "/etc/shadow",
                "/etc/gshadow",
                "/etc/ssh",
                "/root/.ssh",
                "/root/.gnupg",
                "/root/.aws",
                "/var/run/docker.sock",
                "/var/run/docker",
                "/proc",
                "/sys",
            )

        fun filterVolumes(volumes: List<String>): List<String> =
            volumes.filter { vol ->
                val hostPath = vol.substringBefore(":").trimEnd('/')
                !isBlockedPath(hostPath)
            }

        // Blocks /home and /home/<user> but allows deeper paths like /home/klaw/workspace.
        // The workspace volume mount goes through a separate trusted path in buildRunOptions(),
        // so blocking user-level home dirs in volumeMounts config is safe.
        private fun isBlockedPath(hostPath: String): Boolean {
            val normalized = normalizePath(hostPath)
            if (BLOCKED_HOST_PATHS.any { normalized == it || normalized.startsWith("$it/") }) return true
            if (normalized == "/home") return true
            if (normalized.startsWith("/home/") && normalized.removePrefix("/home/").count { it == '/' } == 0) {
                return true
            }
            return false
        }

        private fun normalizePath(path: String): String =
            path
                .replace("//", "/")
                .replace("/./", "/")
                .removeSuffix("/.")
                .trimEnd('/')
    }
}

data class SandboxExecOutput(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
) {
    fun formatForLlm(maxChars: Int = MAX_OUTPUT_CHARS): String {
        val parts = mutableListOf<String>()

        if (timedOut) {
            parts.add("Execution timed out.")
        }

        val truncatedStdout =
            if (stdout.length > maxChars) {
                stdout.take(maxChars) + "\n... output truncated at $maxChars chars ..."
            } else {
                stdout
            }

        if (truncatedStdout.isNotEmpty()) {
            parts.add(truncatedStdout)
        }

        if (stderr.isNotEmpty()) {
            parts.add("stderr:\n$stderr")
        }

        if (exitCode != 0 && !timedOut) {
            parts.add("exit code: $exitCode")
        }

        return if (parts.isEmpty()) "" else parts.joinToString("\n")
    }
}
