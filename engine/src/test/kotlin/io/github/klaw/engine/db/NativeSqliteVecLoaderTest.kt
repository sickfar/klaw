package io.github.klaw.engine.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
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
}
