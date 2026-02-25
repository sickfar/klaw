package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.SocketMessage
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class GatewayBuffer(
    private val bufferPath: String,
) {
    private val lock = ReentrantLock()
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    fun append(message: SocketMessage) {
        lock.withLock {
            val file = File(bufferPath)
            file.parentFile?.mkdirs()
            file.appendText(json.encodeToString<SocketMessage>(message) + "\n")
        }
    }

    fun drain(): List<SocketMessage> {
        return lock.withLock {
            val file = File(bufferPath)
            if (!file.exists()) return@withLock emptyList()
            val lines = file.readLines()
            file.delete()
            lines.mapNotNull { line ->
                if (line.isBlank()) {
                    null
                } else {
                    try {
                        json.decodeFromString<SocketMessage>(line)
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
    }

    fun isEmpty(): Boolean =
        lock.withLock {
            val file = File(bufferPath)
            !file.exists() || file.length() == 0L
        }
}
