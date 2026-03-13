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

    private val suffix: String =
        if (System.getProperty("os.name").lowercase().contains("mac")) ".dylib" else ".so"

    private val resourceName: String = "/native/vec0$suffix"

    private val resourcePresent: Boolean by lazy {
        @Suppress("TooGenericExceptionCaught")
        try {
            javaClass.getResource(resourceName) != null
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
            val resource = javaClass.getResourceAsStream(resourceName) ?: return
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
            // Strip the suffix — load_extension() SQL function always auto-appends .so/.dylib.
            val pathWithoutSuffix = tempFile.absolutePath.removeSuffix(suffix)
            driver.execute(null, "SELECT load_extension('$pathWithoutSuffix')", 0)
            logger.info { "sqlite-vec extension loaded successfully" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load sqlite-vec extension" }
            extensionLoadable.set(false)
        }
    }
}
