package io.github.klaw.engine.mcp

import io.github.klaw.engine.util.VT
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter

private val logger = KotlinLogging.logger {}

class StdioTransport(
    private val command: String,
    private val args: List<String>,
    private val env: Map<String, String>,
    private val workDir: String?,
) : McpTransport {
    private var process: Process? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null

    override val isOpen: Boolean
        get() = process?.isAlive == true

    suspend fun start() {
        withContext(Dispatchers.VT) {
            val fullCommand = listOf(command) + args
            logger.debug { "Starting stdio transport: ${fullCommand.first()} with ${args.size} args" }

            val pb = ProcessBuilder(fullCommand)
            pb.environment().putAll(env)
            if (workDir != null) {
                pb.directory(File(workDir))
            }
            pb.redirectErrorStream(false)

            val proc = pb.start()
            process = proc
            reader = BufferedReader(InputStreamReader(proc.inputStream))
            writer = BufferedWriter(OutputStreamWriter(proc.outputStream))

            startStderrConsumer(proc)
            logger.trace { "Stdio transport process started, pid=${proc.pid()}" }
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
                    logger.trace { "Stderr: $n bytes" }
                }
            } catch (_: Exception) {
                // process destroyed
            }
        }
    }

    override suspend fun send(message: String) {
        check(isOpen) { "Transport is not open" }
        withContext(Dispatchers.VT) {
            logger.trace { "Sending ${message.length} bytes" }
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
            logger.trace { "Received ${line.length} bytes" }
            line
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.VT) {
            logger.debug { "Closing stdio transport" }
            val proc = process ?: return@withContext
            try {
                writer?.close()
            } catch (_: Exception) {
                // ignore
            }
            proc.destroyForcibly()
            proc.waitFor()
            process = null
            reader = null
            writer = null
            logger.trace { "Stdio transport closed" }
        }
    }

    private companion object {
        const val STDERR_BUFFER_SIZE = 4096
    }
}
