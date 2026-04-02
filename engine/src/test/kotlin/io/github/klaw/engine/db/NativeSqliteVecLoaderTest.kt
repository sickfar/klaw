package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path
import java.util.Properties
import java.util.concurrent.CompletableFuture

class NativeSqliteVecLoaderTest {
    @Test
    fun `isAvailable returns true when classpath resource is present`() {
        val loader = NativeSqliteVecLoader()
        assertTrue(loader.isAvailable(), "vec0 resource should be on the classpath (bundled by downloadSqliteVec)")
    }

    @Test
    fun `isAvailable is consistent and idempotent across concurrent calls`() {
        val loader = NativeSqliteVecLoader()
        val results =
            (1..100)
                .map { CompletableFuture.supplyAsync { loader.isAvailable() } }
                .map { it.get() }
        assertEquals(1, results.distinct().size, "All concurrent results must be identical")
    }

    @Test
    fun `isAvailable returns same result on repeated sequential calls`() {
        val loader = NativeSqliteVecLoader()
        val first = loader.isAvailable()
        val second = loader.isAvailable()
        assertEquals(first, second, "Repeated calls must return same value")
    }

    @Test
    fun `lib file is extracted to platform-specific cache dir`(
        @TempDir tempDir: Path,
    ) {
        val cacheDir = tempDir.toFile().absolutePath
        val loader = NativeSqliteVecLoader(cacheDir)

        loader.loadExtension(createDriver())

        val libFile = File("$cacheDir/native/${loader.platform}/vec0${loader.suffix}")
        assertTrue(libFile.exists(), "lib file should be extracted to cache/native/{platform} dir")
        assertTrue(loader.isAvailable(), "extension should be available after successful load")
    }

    @Test
    fun `native platform dir is created if absent`(
        @TempDir tempDir: Path,
    ) {
        val cacheDir = File(tempDir.toFile(), "nonexistent").absolutePath
        val loader = NativeSqliteVecLoader(cacheDir)

        loader.loadExtension(createDriver())

        val nativeDir = File("$cacheDir/native/${loader.platform}")
        assertTrue(nativeDir.exists(), "native platform dir should be created")
        assertTrue(nativeDir.isDirectory, "native platform path should be a directory")
        assertTrue(loader.isAvailable(), "extension should be available after successful load")
    }

    @Test
    fun `existing lib file is overwritten with correct content`(
        @TempDir tempDir: Path,
    ) {
        val cacheDir = tempDir.toFile().absolutePath
        val loader = NativeSqliteVecLoader(cacheDir)
        val nativeDir = File("$cacheDir/native/${loader.platform}").also { it.mkdirs() }
        val libFile = File(nativeDir, "vec0${loader.suffix}")
        libFile.writeText("garbage content")
        val garbageSize = libFile.length()

        loader.loadExtension(createDriver())

        assertTrue(libFile.length() > garbageSize, "lib file should be overwritten with real content")
        assertTrue(loader.isAvailable(), "extension should be available after overwrite")
    }

    private fun createDriver(): JdbcSqliteDriver {
        val props = Properties()
        props["enable_load_extension"] = "true"
        return JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, props)
    }
}
