package io.github.klaw.engine.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture

class NativeSqliteVecLoaderTest {
    @Test
    fun `isAvailable returns false when classpath resource is absent`() {
        // /native/vec0 is not on the test classpath
        val loader = NativeSqliteVecLoader()
        assertFalse(loader.isAvailable())
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
