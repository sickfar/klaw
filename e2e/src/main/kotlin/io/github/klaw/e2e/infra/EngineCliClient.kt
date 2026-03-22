package io.github.klaw.e2e.infra

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.Socket

private val logger = KotlinLogging.logger {}

/**
 * Local redeclaration of CliRequestMessage for E2E tests.
 * Avoids pulling in the :common KMP module dependency.
 */
@Serializable
data class CliRequestMessage(
    val command: String,
    val params: Map<String, String> = emptyMap(),
)

/**
 * JVM TCP socket client for sending CLI commands to Engine.
 * Mirrors the real CLI's EngineSocketClient protocol: send JSON + newline, read response line.
 */
class EngineCliClient(
    private val host: String,
    private val port: Int,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun request(
        command: String,
        params: Map<String, String> = emptyMap(),
    ): String {
        logger.debug { "CLI request: command=$command paramKeys=${params.keys}" }
        val message = json.encodeToString(CliRequestMessage.serializer(), CliRequestMessage(command, params))
        Socket(host, port).use { socket ->
            socket.soTimeout = RECV_TIMEOUT_MS
            val writer = PrintWriter(socket.getOutputStream(), true)
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            writer.println(message)
            val response = reader.readLine() ?: ""
            logger.debug { "CLI response length=${response.length}" }
            return response.replace("\\n", "\n").trim()
        }
    }

    companion object {
        private const val RECV_TIMEOUT_MS = 30_000
    }
}
