package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.github.klaw.common.paths.KlawPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Inject
import jakarta.inject.Singleton
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@Singleton
class NativeSqliteVecLoader(
    private val cacheDir: String,
) : SqliteVecLoader {
    @Inject
    constructor() : this(KlawPaths.cache)
    private val logger = KotlinLogging.logger {}

    internal val suffix: String =
        if (System.getProperty("os.name").lowercase().contains("mac")) ".dylib" else ".so"

    private val resourceName: String = "/native/vec0$suffix"

    init {
        require(!cacheDir.contains("'")) { "cacheDir must not contain single quotes" }
    }

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
            val nativeDir = File("$cacheDir/native")
            if (!nativeDir.exists() && !nativeDir.mkdirs()) {
                logger.warn { "Failed to create native lib dir: ${nativeDir.absolutePath}" }
                extensionLoadable.set(false)
                return
            }
            val libFile = File(nativeDir, "vec0$suffix")
            val tmpFile = File(nativeDir, "vec0$suffix.tmp")
            resource.use { input -> tmpFile.outputStream().use { output -> input.copyTo(output) } }
            if (!tmpFile.setExecutable(true, true)) {
                logger.warn { "Could not set executable bit on ${tmpFile.name} — dlopen may fail" }
            }
            // Atomic rename avoids partial-read by concurrent dlopen on the same file.
            check(tmpFile.renameTo(libFile)) { "Atomic rename of ${tmpFile.name} failed" }
            // Strip the suffix — load_extension() SQL function always auto-appends .so/.dylib.
            val pathWithoutSuffix = libFile.absolutePath.removeSuffix(suffix)
            driver.execute(null, "SELECT load_extension('$pathWithoutSuffix')", 0)
            logger.info { "sqlite-vec extension loaded successfully" }
        } catch (e: Exception) {
            logger.warn(e) { "Failed to load sqlite-vec extension" }
            extensionLoadable.set(false)
        }
    }
}
