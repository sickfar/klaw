package io.github.klaw.engine.socket

import io.github.klaw.common.paths.KlawPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.nio.file.attribute.PosixFilePermissions

private val logger = KotlinLogging.logger {}

private const val DEFAULT_SOCKET_PERMS = "rw-------"

@Factory
class SocketFactory {
    @Singleton
    fun engineSocketServer(handler: SocketMessageHandler): EngineSocketServer {
        val raw = System.getenv("KLAW_SOCKET_PERMS")
        val perms =
            if (raw != null) {
                try {
                    PosixFilePermissions.fromString(raw)
                    raw
                } catch (_: IllegalArgumentException) {
                    logger.warn { "Invalid KLAW_SOCKET_PERMS value, falling back to default" }
                    DEFAULT_SOCKET_PERMS
                }
            } else {
                DEFAULT_SOCKET_PERMS
            }
        return EngineSocketServer(KlawPaths.engineSocket, handler, socketPerms = perms)
    }
}
