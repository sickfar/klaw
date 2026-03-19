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
    val entrypoint: String? = null,
    val detach: Boolean = false,
    val remove: Boolean = false,
    val privileged: Boolean = false,
    val pidHost: Boolean = false,
    val user: String? = null,
    val capDrop: List<String> = emptyList(),
    val securityOpts: List<String> = emptyList(),
    val pidsLimit: Int? = null,
)

data class ExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val timedOut: Boolean = false,
)

interface DockerClient {
    suspend fun run(options: DockerRunOptions): ExecutionResult

    suspend fun exec(
        containerId: String,
        cmd: List<String>,
        timeoutSeconds: Int,
    ): ExecutionResult

    suspend fun stop(containerId: String)

    suspend fun rm(containerId: String)

    suspend fun listContainers(nameFilter: String): List<String>
}

@Singleton
class ProcessDockerClient : DockerClient {
    override suspend fun run(options: DockerRunOptions): ExecutionResult =
        withContext(Dispatchers.VT) {
            val cmd = buildRunCommand(options)
            logger.trace {
                "docker run: image=${options.image} name=${options.name} " +
                    "volumes=${options.volumes.size} network=${options.networkMode} " +
                    "detach=${options.detach} remove=${options.remove} cmdArgs=${cmd.size}"
            }
            val process =
                ProcessBuilder(cmd)
                    .redirectErrorStream(false)
                    .start()
            // Drain streams concurrently to avoid pipe buffer deadlock
            var stdout = ""
            var stderr = ""
            val stdoutThread =
                Thread {
                    stdout =
                        process.inputStream
                            .bufferedReader()
                            .readText()
                            .trim()
                }
            val stderrThread =
                Thread {
                    stderr =
                        process.errorStream
                            .bufferedReader()
                            .readText()
                            .trim()
                }
            stdoutThread.start()
            stderrThread.start()
            val exitCode = process.waitFor()
            stdoutThread.join(STREAM_JOIN_TIMEOUT_MS)
            stderrThread.join(STREAM_JOIN_TIMEOUT_MS)
            if (isDockerInfrastructureError(exitCode, stderr)) {
                logger.debug {
                    "docker run infrastructure error: exitCode=$exitCode " +
                        "stderrLen=${stderr.length} image=${options.image}"
                }
                throwTypedRunException(stderr, options.image)
            }
            ExecutionResult(
                stdout = stdout,
                stderr = stderr,
                exitCode = exitCode,
            )
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
                logger.warn { "docker exec: timed out after ${timeoutSeconds}s" }
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

    override suspend fun listContainers(nameFilter: String): List<String> =
        withContext(Dispatchers.VT) {
            val cmd = listOf("docker", "ps", "-a", "--filter", "name=$nameFilter", "--format", "{{.Names}}")
            logger.trace { "docker ps: filter=$nameFilter" }
            val process =
                ProcessBuilder(cmd)
                    .redirectErrorStream(true)
                    .start()
            val output =
                process.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            process.waitFor()
            if (output.isEmpty()) emptyList() else output.lines()
        }

    private fun buildRunCommand(options: DockerRunOptions): List<String> {
        val cmd = mutableListOf("docker", "run")
        addRunFlags(cmd, options)
        addResourceLimits(cmd, options)
        addSecurityOptions(cmd, options)
        addMounts(cmd, options)
        addEntrypointAndCommand(cmd, options)
        return cmd
    }

    private fun addRunFlags(
        cmd: MutableList<String>,
        options: DockerRunOptions,
    ) {
        if (options.detach) cmd.add("-d")
        if (options.remove) cmd.add("--rm")
        cmd.addAll(listOf("--name", options.name))
    }

    private fun addResourceLimits(
        cmd: MutableList<String>,
        options: DockerRunOptions,
    ) {
        cmd.addAll(listOf("--memory", options.memoryLimit))
        cmd.addAll(listOf("--cpus", options.cpuLimit))
        options.pidsLimit?.let { cmd.addAll(listOf("--pids-limit", it.toString())) }
    }

    private fun addSecurityOptions(
        cmd: MutableList<String>,
        options: DockerRunOptions,
    ) {
        if (options.readOnly) cmd.add("--read-only")
        cmd.addAll(listOf("--network", options.networkMode))
        options.user?.let { cmd.addAll(listOf("--user", it)) }
        options.capDrop.forEach { cmd.addAll(listOf("--cap-drop", it)) }
        options.securityOpts.forEach { cmd.addAll(listOf("--security-opt", it)) }
    }

    private fun addMounts(
        cmd: MutableList<String>,
        options: DockerRunOptions,
    ) {
        options.tmpfs.forEach { (path, opts) ->
            cmd.addAll(listOf("--tmpfs", "$path:$opts"))
        }
        options.volumes.forEach { vol ->
            cmd.addAll(listOf("-v", vol))
        }
    }

    private fun addEntrypointAndCommand(
        cmd: MutableList<String>,
        options: DockerRunOptions,
    ) {
        options.entrypoint?.let { cmd.addAll(listOf("--entrypoint", it)) }
        cmd.add(options.image)
        cmd.addAll(options.command)
    }

    companion object {
        private const val STREAM_JOIN_TIMEOUT_MS = 5000L
        private const val TIMEOUT_EXIT_CODE = 137
        private const val DOCKER_DAEMON_ERROR_EXIT = 125

        /**
         * Docker exits with 125 for daemon/infrastructure errors.
         * Exit codes 126, 127, and others come from the container command itself.
         */
        internal fun isDockerInfrastructureError(
            exitCode: Int,
            stderr: String,
        ): Boolean {
            if (exitCode == DOCKER_DAEMON_ERROR_EXIT) return true
            val lower = stderr.lowercase()
            return lower.contains("cannot connect to the docker daemon") ||
                lower.contains("is docker installed")
        }

        internal fun throwTypedRunException(
            stderr: String,
            image: String,
        ): Nothing {
            val lower = stderr.lowercase()
            throw classifyRunError(lower, stderr, image)
        }

        private fun classifyRunError(
            lower: String,
            stderr: String,
            image: String,
        ): SandboxExecutionException =
            when {
                isDockerUnavailableError(lower) -> {
                    logger.debug { "docker error classified as DockerUnavailable, stderrLen=${stderr.length}" }
                    SandboxExecutionException.DockerUnavailable()
                }

                isImageNotFoundError(lower) -> {
                    logger.debug { "docker error classified as ImageNotFound, image=$image" }
                    SandboxExecutionException.ImageNotFound(image)
                }

                lower.contains("permission denied") -> {
                    logger.debug { "docker error classified as PermissionDenied, stderrLen=${stderr.length}" }
                    SandboxExecutionException.PermissionDenied()
                }

                else -> {
                    logger.debug { "docker error classified as ContainerStartFailure, stderrLen=${stderr.length}" }
                    SandboxExecutionException.ContainerStartFailure(sanitizeErrorDetail(stderr))
                }
            }

        private fun isDockerUnavailableError(lower: String): Boolean =
            lower.contains("cannot connect to the docker daemon") ||
                lower.contains("is docker installed") ||
                lower.contains("command not found") ||
                (lower.contains("no such file or directory") && lower.contains("docker"))

        private fun isImageNotFoundError(lower: String): Boolean =
            lower.contains("no such image") ||
                lower.contains("pull access denied") ||
                lower.contains("manifest unknown")

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
