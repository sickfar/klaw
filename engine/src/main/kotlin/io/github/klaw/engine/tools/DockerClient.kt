package io.github.klaw.engine.tools

import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

private val logger = KotlinLogging.logger {}

sealed class SandboxExecutionException(
    message: String,
) : RuntimeException(message) {
    class DockerUnavailable :
        SandboxExecutionException(
            "Docker is not available. Code execution requires Docker to be installed and running.",
        )

    class ImageNotFound(
        image: String,
    ) : SandboxExecutionException(
            "Docker image '$image' not found. The sandbox image needs to be pulled or built first.",
        )

    class ContainerStartFailure(
        detail: String,
    ) : SandboxExecutionException(
            "Failed to start sandbox container: $detail",
        )

    class OutOfMemory(
        limit: String,
    ) : SandboxExecutionException(
            "Code execution ran out of memory (limit: $limit).",
        )

    class PermissionDenied :
        SandboxExecutionException(
            "Permission denied in sandbox container.",
        )
}

data class DockerRunOptions(
    val image: String,
    val name: String,
    val memoryLimit: String,
    val cpuLimit: String,
    val readOnly: Boolean = true,
    val networkMode: String,
    val tmpfs: Map<String, String> = emptyMap(),
    val volumes: List<String> = emptyList(),
    val command: List<String> = listOf("sleep", "infinity"),
    val detach: Boolean = false,
    val remove: Boolean = false,
    val privileged: Boolean = false,
    val pidHost: Boolean = false,
)

data class ExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
)

interface DockerClient {
    suspend fun run(options: DockerRunOptions): String

    suspend fun exec(
        containerId: String,
        cmd: List<String>,
        timeoutSeconds: Int,
    ): ExecutionResult

    suspend fun stop(containerId: String)

    suspend fun rm(containerId: String)
}

@Singleton
class ProcessDockerClient : DockerClient {
    override suspend fun run(options: DockerRunOptions): String =
        withContext(Dispatchers.VT) {
            val cmd = buildRunCommand(options)
            logger.trace { "docker run: ${cmd.size} args" }
            val process =
                ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start()
            val stdout =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            val stderr =
                process.errorStream
                    .bufferedReader()
                    .readText()
                    .trim()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throwTypedRunException(stderr, options.image)
            }
            stdout
        }

    override suspend fun exec(
        containerId: String,
        cmd: List<String>,
        timeoutSeconds: Int,
    ): ExecutionResult =
        withContext(Dispatchers.VT) {
            val fullCmd = listOf("docker", "exec", containerId) + cmd
            logger.trace { "docker exec: ${fullCmd.size} args, timeout=${timeoutSeconds}s" }
            val process =
                ProcessBuilder(fullCmd)
                    .redirectErrorStream(false)
                    .start()
            // Drain streams concurrently to avoid pipe buffer deadlock
            var stdout = ""
            var stderr = ""
            val stdoutThread = Thread { stdout = process.inputStream.bufferedReader().readText() }
            val stderrThread = Thread { stderr = process.errorStream.bufferedReader().readText() }
            stdoutThread.start()
            stderrThread.start()
            val completed = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                stdoutThread.join(STREAM_JOIN_TIMEOUT_MS)
                stderrThread.join(STREAM_JOIN_TIMEOUT_MS)
                return@withContext ExecutionResult(
                    stdout = "",
                    stderr = "",
                    exitCode = TIMEOUT_EXIT_CODE,
                    timedOut = true,
                )
            }
            stdoutThread.join(STREAM_JOIN_TIMEOUT_MS)
            stderrThread.join(STREAM_JOIN_TIMEOUT_MS)
            ExecutionResult(
                stdout = stdout,
                stderr = stderr,
                exitCode = process.exitValue(),
            )
        }

    override suspend fun stop(containerId: String) {
        withContext(Dispatchers.VT) {
            logger.trace { "docker stop: container" }
            ProcessBuilder("docker", "stop", "-t", "5", containerId)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
    }

    override suspend fun rm(containerId: String) {
        withContext(Dispatchers.VT) {
            logger.trace { "docker rm: container" }
            ProcessBuilder("docker", "rm", "-f", containerId)
                .redirectErrorStream(true)
                .start()
                .waitFor()
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun buildRunCommand(options: DockerRunOptions): List<String> {
        val cmd = mutableListOf("docker", "run")
        if (options.detach) cmd.add("-d")
        if (options.remove) cmd.add("--rm")
        cmd.addAll(listOf("--name", options.name))
        cmd.addAll(listOf("--memory", options.memoryLimit))
        cmd.addAll(listOf("--cpus", options.cpuLimit))
        if (options.readOnly) cmd.add("--read-only")
        cmd.addAll(listOf("--network", options.networkMode))
        options.tmpfs.forEach { (path, opts) ->
            cmd.addAll(listOf("--tmpfs", "$path:$opts"))
        }
        options.volumes.forEach { vol ->
            cmd.addAll(listOf("-v", vol))
        }
        cmd.add(options.image)
        cmd.addAll(options.command)
        return cmd
    }

    companion object {
        private const val STREAM_JOIN_TIMEOUT_MS = 5000L
        private const val TIMEOUT_EXIT_CODE = 137

        @Suppress("ThrowsCount", "ComplexCondition")
        internal fun throwTypedRunException(
            stderr: String,
            image: String,
        ): Nothing {
            val lower = stderr.lowercase()
            when {
                lower.contains("cannot connect to the docker daemon") ||
                    lower.contains("is docker installed") ||
                    lower.contains("command not found") ||
                    (lower.contains("no such file or directory") && lower.contains("docker")) -> {
                    throw SandboxExecutionException.DockerUnavailable()
                }

                lower.contains("no such image") ||
                    lower.contains("pull access denied") ||
                    lower.contains("manifest unknown") -> {
                    throw SandboxExecutionException.ImageNotFound(image)
                }

                lower.contains("permission denied") -> {
                    throw SandboxExecutionException.PermissionDenied()
                }

                else -> {
                    throw SandboxExecutionException.ContainerStartFailure(
                        sanitizeErrorDetail(stderr),
                    )
                }
            }
        }

        internal fun classifyExecResult(
            result: ExecutionResult,
            memoryLimit: String,
        ): ExecutionResult {
            if (result.timedOut) return result
            val lower = result.stderr.lowercase()
            if (result.exitCode == OOM_EXIT_CODE && looksLikeOom(lower)) {
                throw SandboxExecutionException.OutOfMemory(memoryLimit)
            }
            if (lower.contains("permission denied")) {
                throw SandboxExecutionException.PermissionDenied()
            }
            return result
        }

        private fun sanitizeErrorDetail(stderr: String): String {
            val first = stderr.lineSequence().firstOrNull()?.trim() ?: "unknown error"
            val sanitized = first.replace(Regex("/[\\w./]+"), "<path>")
            return sanitized.take(MAX_ERROR_DETAIL_LENGTH)
        }

        private val OOM_PATTERNS = listOf("killed", "oom")

        // Empty stderr + exit 137 is treated as OOM because Docker's OOM killer
        // often produces no stderr output before SIGKILL.
        private fun looksLikeOom(lower: String): Boolean = lower.isEmpty() || OOM_PATTERNS.any { lower.contains(it) }

        private const val MAX_ERROR_DETAIL_LENGTH = 200
        private const val OOM_EXIT_CODE = 137
    }
}
