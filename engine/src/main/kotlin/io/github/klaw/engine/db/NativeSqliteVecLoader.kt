package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
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
    override fun loadExtension(driver: JdbcSqliteDriver) {
        if (!isAvailable()) return
        try {
            val resource = javaClass.getResourceAsStream("/native/vec0") ?: return
            // Create temp file with owner-only rwx permissions before writing the binary.
            // This prevents local users from reading or replacing the library between creation and load.
            val suffix = if (System.getProperty("os.name").lowercase().contains("mac")) ".dylib" else ".so"
            val tempPath =
                runCatching {
                    Files.createTempFile(
                        "vec0",
                        suffix,
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
                    )
                }.getOrElse { Files.createTempFile("vec0", suffix) }
            val tempFile = tempPath.toFile()
            tempFile.deleteOnExit()
            resource.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
            // Use two-arg load_extension(path, entrypoint) — when entrypoint is specified,
            // SQLite does NOT auto-append a platform suffix to the path, avoiding .so.so errors.
            driver.execute(
                null,
                "SELECT load_extension('${tempFile.absolutePath}', 'sqlite3_vec_init')",
                0,
            )
            logger.info { "sqlite-vec extension loaded successfully" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load sqlite-vec extension" }
            extensionLoadable.set(false)
        }
    }
}
