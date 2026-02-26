package io.github.klaw.gateway.socket

import io.github.klaw.common.protocol.SocketMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

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
            // Create the file with owner-only permissions before any data is written.
            // This eliminates the TOCTOU window between file creation and permission setting.
            if (!file.exists()) {
                runCatching {
                    Files.createFile(
                        file.toPath(),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                }.onFailure { e ->
                    logger.warn { "Could not create buffer file with restricted permissions: ${e::class.simpleName}" }
                }
            }
            file.appendText(json.encodeToString<SocketMessage>(message) + "\n")
        }
    }

    fun drain(): List<SocketMessage> {
        return lock.withLock {
            val file = File(bufferPath)
            if (!file.exists() || file.length() == 0L) return@withLock emptyList()

            // Atomically move the file so new appends can proceed immediately.
            val tempFile = File("$bufferPath.tmp")
            Files.move(file.toPath(), tempFile.toPath(), StandardCopyOption.ATOMIC_MOVE)

            val messages = mutableListOf<SocketMessage>()
            tempFile.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                        @Suppress("TooGenericExceptionCaught")
                        try {
                            messages.add(json.decodeFromString<SocketMessage>(line))
                        } catch (e: Exception) {
                            logger.warn { "Skipping malformed buffer line: ${e::class.simpleName}" }
                            // Skip malformed lines — partial writes or corruption.
                        }
                    }
                }
            }
            tempFile.delete()
            messages
        }
    }

    fun isEmpty(): Boolean =
        lock.withLock {
            val file = File(bufferPath)
            !file.exists() || file.length() == 0L
        }
}
