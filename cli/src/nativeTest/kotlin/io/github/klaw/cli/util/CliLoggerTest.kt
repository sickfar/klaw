package io.github.klaw.cli.util

import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Suppress("TooManyFunctions")
class CliLoggerTest {
    private val baseDir = "/tmp/klaw-logger-test-${getpid()}"
    private val logDir = "$baseDir/logs"

    @BeforeTest
    fun setup() {
        CliLogger.close()
        mkdir(baseDir, 0x1EDu)
    }

    @AfterTest
    fun cleanup() {
        CliLogger.close()
        unlink("$logDir/cli.log")
        rmdir(logDir)
        // also try fallback dir cleanup
        unlink("$baseDir/fallback/cli.log")
        rmdir("$baseDir/fallback")
        rmdir(baseDir)
    }

    @Test
    fun `logger writes to cli log in specified dir`() {
        CliLogger.init(logDir = logDir)
        CliLogger.info { "hello world" }
        CliLogger.close()

        val content = readFileText("$logDir/cli.log")
        assertNotNull(content)
        assertContains(content, "hello world")
    }

    @Test
    fun `log line format contains timestamp level and message`() {
        CliLogger.init(logDir = logDir)
        CliLogger.info { "test message" }
        CliLogger.close()

        val content = readFileText("$logDir/cli.log")
        assertNotNull(content)
        // Format: YYYY-MM-DDTHH:mm:ss.SSSZ INFO test message
        val line = content.lines().first { it.isNotBlank() }
        assertContains(line, "INFO")
        assertContains(line, "test message")
        // ISO-8601 timestamp starts with digit
        assertTrue(line.first().isDigit(), "Line should start with timestamp: $line")
    }

    @Test
    fun `debug message not written when level is INFO`() {
        CliLogger.init(logDir = logDir, level = CliLogger.Level.INFO)
        CliLogger.debug { "should not appear" }
        CliLogger.info { "should appear" }
        CliLogger.close()

        val content = readFileText("$logDir/cli.log")
        assertNotNull(content)
        assertFalse(content.contains("should not appear"))
        assertContains(content, "should appear")
    }

    @Test
    fun `error message always written regardless of level`() {
        CliLogger.init(logDir = logDir, level = CliLogger.Level.INFO)
        CliLogger.error { "critical error" }
        CliLogger.close()

        val content = readFileText("$logDir/cli.log")
        assertNotNull(content)
        assertContains(content, "ERROR")
        assertContains(content, "critical error")
    }

    @Test
    fun `debug message written when level is DEBUG`() {
        CliLogger.init(logDir = logDir, level = CliLogger.Level.DEBUG)
        CliLogger.debug { "debug trace" }
        CliLogger.close()

        val content = readFileText("$logDir/cli.log")
        assertNotNull(content)
        assertContains(content, "DEBUG")
        assertContains(content, "debug trace")
    }

    @Test
    fun `falls back to fallback dir when primary dir parent does not exist`() {
        val fallbackDir = "$baseDir/fallback"
        CliLogger.init(logDir = "/nonexistent/should/not/exist", fallbackDir = fallbackDir)
        CliLogger.info { "fallback message" }
        CliLogger.close()

        val content = readFileText("$fallbackDir/cli.log")
        assertNotNull(content)
        assertContains(content, "fallback message")
    }

    @Test
    fun `creates log directory if parent exists but logs subdir does not`() {
        // logDir doesn't exist yet but baseDir does
        assertFalse(isDirectory(logDir))
        CliLogger.init(logDir = logDir)
        CliLogger.info { "after mkdir" }
        CliLogger.close()

        assertTrue(isDirectory(logDir))
        val content = readFileText("$logDir/cli.log")
        assertNotNull(content)
        assertContains(content, "after mkdir")
    }

    @Test
    fun `init is idempotent`() {
        CliLogger.init(logDir = logDir)
        CliLogger.info { "first" }
        CliLogger.init(logDir = logDir)
        CliLogger.info { "second" }
        CliLogger.close()

        val content = readFileText("$logDir/cli.log")
        assertNotNull(content)
        assertContains(content, "first")
        assertContains(content, "second")
    }

    @Test
    fun `calling log methods before init does not crash`() {
        // No init call — should silently no-op
        CliLogger.debug { "no crash" }
        CliLogger.info { "no crash" }
        CliLogger.warn { "no crash" }
        CliLogger.error { "no crash" }
        // If we get here without exception, test passes
    }

    @Test
    fun `close flushes and subsequent writes are dropped`() {
        CliLogger.init(logDir = logDir)
        CliLogger.info { "before close" }
        CliLogger.close()
        CliLogger.info { "after close" }

        val content = readFileText("$logDir/cli.log")
        assertNotNull(content)
        assertContains(content, "before close")
        assertFalse(content.contains("after close"))
    }

    @Test
    fun `lambda not evaluated when level is filtered out`() {
        var evaluated = false
        CliLogger.init(logDir = logDir, level = CliLogger.Level.ERROR)
        CliLogger.debug {
            evaluated = true
            "should not eval"
        }
        CliLogger.close()

        assertFalse(evaluated, "Lambda should not be evaluated when level is filtered")
    }

    @Test
    fun `warn message written when level is INFO`() {
        CliLogger.init(logDir = logDir, level = CliLogger.Level.INFO)
        CliLogger.warn { "warning message" }
        CliLogger.close()

        val content = readFileText("$logDir/cli.log")
        assertNotNull(content)
        assertContains(content, "WARN")
        assertContains(content, "warning message")
    }
}
