package io.github.klaw.engine.tools

import io.github.klaw.common.config.CodeExecutionConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import java.util.UUID
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

private val logger = KotlinLogging.logger {}

const val SANDBOX_WORKSPACE_PATH = "/workspace"
private const val MAX_OUTPUT_CHARS = 10_000

class SandboxManager(
    private val config: CodeExecutionConfig,
    private val docker: DockerClient,
    private val workspacePath: String? = null,
    private val hostWorkspacePath: String? = null,
) {
    private var containerId: String? = null
    private var executionCount = 0
    private var lastExecutionTime: Instant = Instant.DISTANT_PAST

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
        val output = docker.run(options)
        return SandboxExecOutput(stdout = output, stderr = "", exitCode = 0)
    }

    private suspend fun getOrCreateContainer(): String {
        val current = containerId
        val shouldRecreate =
            current == null ||
                executionCount >= config.keepAliveMaxExecutions ||
                isIdleTimeoutExpired()

        if (shouldRecreate) {
            if (current != null) {
                destroyContainer(current)
            }
            val name = "klaw-sandbox-${UUID.randomUUID()}"
            val options = buildRunOptions(name, detach = true)
            val id = docker.run(options)
            containerId = id
            executionCount = 0
            lastExecutionTime = Instant.fromEpochMilliseconds(System.currentTimeMillis())
            logger.debug { "Created keep-alive container" }
            return id
        }
        return current
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
        logger.debug { "SandboxManager shutdown complete" }
    }

    private suspend fun destroyContainer(id: String) {
        try {
            docker.stop(id)
            docker.rm(id)
        } catch (_: Exception) {
            logger.trace { "Container cleanup completed with warnings" }
        }
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
            image = config.dockerImage,
            name = name,
            memoryLimit = config.maxMemory,
            cpuLimit = config.maxCpus,
            readOnly = true,
            networkMode = if (config.allowNetwork) "bridge" else "none",
            tmpfs = mapOf("/tmp" to "rw,size=64m"),
            volumes = safeVolumes,
            command = command,
            detach = detach,
            remove = remove,
        )
    }

    companion object {
        fun filterVolumes(volumes: List<String>): List<String> =
            volumes.filter { vol ->
                val hostPath = vol.substringBefore(":")
                !hostPath.contains("docker.sock")
            }
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
