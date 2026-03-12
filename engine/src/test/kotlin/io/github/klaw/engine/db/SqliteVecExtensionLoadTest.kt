package io.github.klaw.engine.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.util.Properties

class SqliteVecExtensionLoadTest {
    @Test
    fun `extension loads successfully with enable_load_extension property`() {
        val props = Properties()
        props["enable_load_extension"] = "true"
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, props)
        KlawDatabase.Schema.create(driver)

        val loader = NativeSqliteVecLoader()
        loader.loadExtension(driver)

        assertTrue(loader.isAvailable(), "sqlite-vec should be available after loading")
    }

    @Test
    fun `vec0 virtual table can be created after extension load`() {
        val props = Properties()
        props["enable_load_extension"] = "true"
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, props)
        KlawDatabase.Schema.create(driver)

        val loader = NativeSqliteVecLoader()
        loader.loadExtension(driver)

        if (!loader.isAvailable()) return // Skip on environments without the native binary

        VirtualTableSetup.createVirtualTables(driver, sqliteVecAvailable = true)

        // Verify vec_memory table exists and is queryable
        val result =
            driver
                .executeQuery(
                    null,
                    "SELECT count(*) FROM vec_memory",
                    { cursor ->
                        cursor.next()
                        app.cash.sqldelight.db.QueryResult
                            .Value(cursor.getLong(0)!!)
                    },
                    0,
                ).value
        assertEquals(0L, result)
    }

    @Test
    fun `extension fails without enable_load_extension property`() {
        assumeTrue(
            javaClass.getResource("/native/vec0") != null,
            "Skipping: vec0 native binary not on classpath",
        )

        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        KlawDatabase.Schema.create(driver)

        val loader = NativeSqliteVecLoader()
        loader.loadExtension(driver)

        assertFalse(loader.isAvailable(), "Extension should fail to load without enable_load_extension")
    }
}
