package io.github.klaw.engine.db

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.util.concurrent.atomic.AtomicBoolean

@Singleton
class NativeSqliteVecLoader : SqliteVecLoader {
    private val logger = KotlinLogging.logger {}

    private val resourcePresent: Boolean by lazy {
        @Suppress("TooGenericExceptionCaught")
        try {
            javaClass.getResource("/native/vec0") != null
        } catch (_: Exception) {
            false
        }
    }

    // Set to false if extension loading fails at runtime despite resource being present.
    private val extensionLoadable = AtomicBoolean(true)

    override fun isAvailable(): Boolean = resourcePresent && extensionLoadable.get()

    @Suppress("TooGenericExceptionCaught")
    override fun loadExtension(connection: Connection) {
        if (!isAvailable()) return
        try {
            val resource = javaClass.getResourceAsStream("/native/vec0") ?: return
            // Create temp file with owner-only rwx permissions before writing the binary.
            // This prevents local users from reading or replacing the .so between creation and load.
            val tempPath =
                runCatching {
                    Files.createTempFile(
                        "vec0",
                        ".so",
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                    )
                }.getOrElse { Files.createTempFile("vec0", ".so") }
            val tempFile = tempPath.toFile()
            tempFile.deleteOnExit()
            resource.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
            connection.createStatement().use { stmt ->
                stmt.execute("SELECT load_extension('${tempFile.absolutePath}')")
            }
            logger.info { "sqlite-vec extension loaded successfully" }
        } catch (e: Exception) {
            logger.warn { "Failed to load sqlite-vec extension: ${e.message}" }
            extensionLoadable.set(false)
        }
    }
}
