package io.github.klaw.engine.db

import jakarta.inject.Singleton
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Connection

@Singleton
class NativeSqliteVecLoader : SqliteVecLoader {
    private val log = LoggerFactory.getLogger(NativeSqliteVecLoader::class.java)
    private var available: Boolean? = null

    @Suppress("TooGenericExceptionCaught")
    override fun isAvailable(): Boolean =
        available ?: try {
            val resource = javaClass.getResource("/native/vec0")
            (resource != null).also { available = it }
        } catch (_: Exception) {
            available = false
            false
        }

    @Suppress("TooGenericExceptionCaught")
    override fun loadExtension(connection: Connection) {
        if (!isAvailable()) return
        try {
            val resource = javaClass.getResourceAsStream("/native/vec0") ?: return
            val tempFile = File.createTempFile("vec0", ".so")
            tempFile.deleteOnExit()
            resource.use { input -> tempFile.outputStream().use { output -> input.copyTo(output) } }
            connection.createStatement().use { stmt ->
                stmt.execute("SELECT load_extension('${tempFile.absolutePath}')")
            }
            log.info("sqlite-vec extension loaded successfully")
        } catch (e: Exception) {
            log.warn("Failed to load sqlite-vec extension: ${e.message}")
            available = false
        }
    }
}
