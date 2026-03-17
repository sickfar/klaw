package io.github.klaw.engine.socket

import io.github.klaw.common.protocol.SocketMessage
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.SerializationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private val logger = KotlinLogging.logger {}

class EngineOutboundBuffer(
    private val bufferPath: String,
    private val maxLines: Int = 10_000,
) {
    private val lock = ReentrantLock()
    private val file = File(bufferPath)
    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
    private var lineCount: Int = -1 // -1 means not yet initialized

    private fun ensureLineCount(): Int {
        if (lineCount < 0) {
            lineCount = if (file.exists()) file.useLines { lines -> lines.count { it.isNotBlank() } } else 0
        }
        return lineCount
    }

    fun append(message: SocketMessage) {
        lock.withLock {
            file.parentFile?.mkdirs()
            if (!file.exists()) {
                runCatching {
                    Files.createFile(
                        file.toPath(),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")),
                    )
                }.onFailure { e ->
                    logger.warn(e) { "Could not create buffer file with restricted permissions" }
                }
                lineCount = 0
            }
            val encoded = json.encodeToString<SocketMessage>(message) + "\n"
            val count = ensureLineCount()
            if (count >= maxLines) {
                // Truncate oldest lines — only needed when at capacity
                val existingLines = file.readLines().filter { it.isNotBlank() }
                val kept = existingLines.drop(existingLines.size - maxLines + 1)
                file.writeText(kept.joinToString("\n") + "\n" + encoded)
                lineCount = kept.size + 1
            } else {
                file.appendText(encoded)
                lineCount = count + 1
            }
        }
    }

    fun drain(): List<SocketMessage> {
        return lock.withLock {
            if (!file.exists() || file.length() == 0L) return@withLock emptyList()

            val tempFile = File("$bufferPath.tmp")
            Files.move(file.toPath(), tempFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            lineCount = 0

            val messages = mutableListOf<SocketMessage>()
            tempFile.bufferedReader().use { reader ->
                reader.forEachLine { line ->
                    if (line.isNotBlank()) {
                        try {
                            messages.add(json.decodeFromString<SocketMessage>(line))
                        } catch (e: SerializationException) {
                            logger.warn(e) { "Skipping malformed buffer line" }
                        } catch (e: IllegalArgumentException) {
                            logger.warn(e) { "Skipping malformed buffer line" }
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
            !file.exists() || file.length() == 0L
        }
}
