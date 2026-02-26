package io.github.klaw.engine.scheduler

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SQLiteDelegateTest {
    @Test
    fun `buildProps sets selectWithLockSQL without FOR UPDATE`() {
        val props = QuartzKlawScheduler.buildProps("/tmp/test-scheduler.db")
        val lockSql = props.getProperty("org.quartz.jobStore.selectWithLockSQL")
        assertNotNull(lockSql, "selectWithLockSQL property must be set")
        assertFalse(
            lockSql.contains("FOR UPDATE", ignoreCase = true),
            "SQLite does not support FOR UPDATE, but found: $lockSql",
        )
    }

    @Test
    fun `buildProps lock SQL contains expected components`() {
        val props = QuartzKlawScheduler.buildProps("/tmp/test-scheduler.db")
        val lockSql = props.getProperty("org.quartz.jobStore.selectWithLockSQL")!!
        assertTrue(lockSql.contains("LOCKS"), "Expected LOCKS table in: $lockSql")
        assertTrue(lockSql.contains("LOCK_NAME"), "Expected LOCK_NAME in: $lockSql")
        assertTrue(lockSql.contains("?"), "Expected ? placeholder in: $lockSql")
    }

    @Test
    fun `buildProps uses SQLiteDelegate as driver delegate`() {
        val props = QuartzKlawScheduler.buildProps("/tmp/test-scheduler.db")
        val delegateClass = props.getProperty("org.quartz.jobStore.driverDelegateClass")
        assertTrue(
            delegateClass.contains("SQLiteDelegate"),
            "Expected SQLiteDelegate as driver, got: $delegateClass",
        )
    }

    @Test
    fun `buildProps uses SqliteConnectionProvider`() {
        val props = QuartzKlawScheduler.buildProps("/tmp/test-scheduler.db")
        val providerClass =
            props.getProperty("org.quartz.dataSource.klawScheduler.connectionProvider.class")
        assertNotNull(providerClass, "connectionProvider.class must be set")
        assertTrue(
            providerClass.contains("SqliteConnectionProvider"),
            "Expected SqliteConnectionProvider, got: $providerClass",
        )
    }

    @Test
    fun `toJson and fromJson round-trip preserves string values`() {
        // Verify via the companion object — access via Kotlin companion reflection
        val companion = SQLiteDelegate.Companion
        val toJson = companion::class.java.getDeclaredMethod("toJson", org.quartz.JobDataMap::class.java)
        toJson.isAccessible = true

        val map =
            org.quartz.JobDataMap().apply {
                put("name", "morning-check")
                put("message", "Check emails")
                put("model", "glm/glm-4-plus")
            }
        val json = toJson.invoke(companion, map) as String

        assertTrue(json.contains("morning-check"), "Expected name in JSON: $json")
        assertTrue(json.contains("Check emails"), "Expected message in JSON: $json")
        assertTrue(json.contains("glm/glm-4-plus"), "Expected model in JSON: $json")

        val fromJson = companion::class.java.getDeclaredMethod("fromJson", String::class.java)
        fromJson.isAccessible = true
        val restored = fromJson.invoke(companion, json) as org.quartz.JobDataMap

        assertTrue(restored.getString("name") == "morning-check")
        assertTrue(restored.getString("message") == "Check emails")
        assertTrue(restored.getString("model") == "glm/glm-4-plus")
    }
}
