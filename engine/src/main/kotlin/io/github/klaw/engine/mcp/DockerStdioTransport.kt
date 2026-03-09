package io.github.klaw.engine.mcp

import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter

private val logger = KotlinLogging.logger {}

data class DockerStdioParams(
    val serverName: String,
    val image: String,
    val command: String,
    val args: List<String>,
    val env: Map<String, String>,
    val network: String?,
)

class DockerStdioTransport(
    private val serverName: String,
    private val image: String,
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String>,
    private val network: String? = null,
) : McpTransport {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    private val containerName: String
        get() = "klaw-mcp-$serverName"

    override val isOpen: Boolean
        get() = process?.isAlive == true

    suspend fun start() {
        withContext(Dispatchers.VT) {
            val dockerCmd =
                buildDockerCommand(
                    DockerStdioParams(serverName, image, command, args, env, network),
                )
            logger.debug { "Starting docker stdio transport: $containerName, image=$image" }
            logger.trace { "Docker command: ${dockerCmd.size} parts" }

            val pb = ProcessBuilder(dockerCmd)
            pb.redirectErrorStream(false)

            val proc = pb.start()
            process = proc
            reader = BufferedReader(InputStreamReader(proc.inputStream))
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream))

            startStderrConsumer(proc)
            logger.trace { "Docker transport started, pid=${proc.pid()}" }
        }
    }

    private fun startStderrConsumer(proc: Process) {
        Thread.startVirtualThread {
            try {
                val stderr = proc.errorStream
                val buf = ByteArray(STDERR_BUFFER_SIZE)
                while (true) {
                    val n = stderr.read(buf)
                    if (n < 0) break
                    logger.trace { "Docker stderr: $n bytes" }
                }
            } catch (_: Exception) {
                // process destroyed
            }
        }
    }

    override suspend fun send(message: String) {
        check(isOpen) { "Transport is not open" }
        withContext(Dispatchers.VT) {
            logger.trace { "Sending ${message.length} bytes to $containerName" }
            val w = writer ?: error("Writer not initialized")
            w.write(message)
            w.newLine()
            w.flush()
        }
    }

    override suspend fun receive(): String {
        check(isOpen) { "Transport is not open" }
        return withContext(Dispatchers.VT) {
            val r = reader ?: error("Reader not initialized")
            val line = r.readLine() ?: error("End of stream")
            logger.trace { "Received ${line.length} bytes from $containerName" }
            line
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.VT) {
            logger.debug { "Closing docker transport: $containerName" }
            val proc = process ?: return@withContext
            try {
                writer?.close()
            } catch (_: Exception) {
                // ignore
            }
            proc.destroyForcibly()
            killContainer()
            process = null
            reader = null
            writer = null
            logger.trace { "Docker transport closed: $containerName" }
        }
    }

    private fun killContainer() {
        try {
            val kill =
                ProcessBuilder("docker", "rm", "-f", containerName)
                    .redirectErrorStream(true)
                    .start()
            kill.waitFor()
            logger.trace { "Killed container $containerName" }
        } catch (_: Exception) {
            logger.warn { "Failed to kill container $containerName" }
        }
    }

    companion object {
        private const val STDERR_BUFFER_SIZE = 4096

        fun buildDockerCommand(params: DockerStdioParams): List<String> {
            val cmd = mutableListOf("docker", "run", "-i", "--rm", "--name", "klaw-mcp-${params.serverName}")

            if (params.network != null) {
                cmd.add("--network")
                cmd.add(params.network)
            }

            for ((key, value) in params.env) {
                cmd.add("-e")
                cmd.add("$key=$value")
            }

            cmd.add(params.image)
            cmd.add(params.command)
            cmd.addAll(params.args)

            return cmd
        }
    }
}
