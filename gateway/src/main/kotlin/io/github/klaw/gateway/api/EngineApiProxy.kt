package io.github.klaw.gateway.api

import io.github.klaw.common.paths.KlawPathsSnapshot
import io.github.klaw.common.protocol.CliRequestMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel

private val logger = KotlinLogging.logger {}

@Singleton
class EngineApiProxy(
    private val paths: KlawPathsSnapshot,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun send(
        command: String,
        params: Map<String, String> = emptyMap(),
    ): String =
        withContext(Dispatchers.IO) {
            val request = CliRequestMessage(command, params)
            val addr = InetSocketAddress(paths.engineHost, paths.enginePort)
            try {
                SocketChannel.open().use { channel ->
                    channel.connect(addr)
                    val writer = PrintWriter(Channels.newOutputStream(channel), true)
                    val reader = BufferedReader(InputStreamReader(Channels.newInputStream(channel)))
                    writer.println(json.encodeToString(request))
                    (reader.readLine() ?: """{"error":"empty response from engine"}""")
                        .replace("\\n", "\n")
                }
            } catch (e: java.io.IOException) {
                logger.warn { "Engine proxy failed: ${e::class.simpleName}" }
                null
            }
        } ?: """{"error":"engine unavailable"}"""
}
