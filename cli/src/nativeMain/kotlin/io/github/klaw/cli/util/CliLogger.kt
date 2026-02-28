package io.github.klaw.cli.util

import io.github.klaw.cli.init.mkdirMode755
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fwrite
import kotlin.time.Clock

/** File logger for CLI. Single-threaded use only — not safe for concurrent access. */
@OptIn(ExperimentalForeignApi::class)
object CliLogger {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    private var fileHandle: kotlinx.cinterop.CPointer<FILE>? = null
    private var minLevel: Level = Level.INFO

    fun init(
        logDir: String = "",
        fallbackDir: String = "/tmp/klaw",
        level: Level = Level.INFO,
    ) {
        if (fileHandle != null) return
        minLevel = level
        val dir = resolveDir(logDir, fallbackDir) ?: return
        fileHandle = fopen("$dir/cli.log", "a")
    }

    fun debug(message: () -> String) = log(Level.DEBUG, message)

    fun info(message: () -> String) = log(Level.INFO, message)

    fun warn(message: () -> String) = log(Level.WARN, message)

    fun error(message: () -> String) = log(Level.ERROR, message)

    fun close() {
        val fh = fileHandle ?: return
        fflush(fh)
        fclose(fh)
        fileHandle = null
    }

    private fun log(
        level: Level,
        message: () -> String,
    ) {
        if (level < minLevel) return
        val fh = fileHandle ?: return
        val ts = Clock.System.now().toString()
        val line = "$ts ${level.name} ${message()}\n"
        val bytes = line.encodeToByteArray()
        bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), fh)
        }
        fflush(fh)
    }

    private fun resolveDir(
        primary: String,
        fallback: String,
    ): String? {
        if (primary.isNotEmpty() && tryMakeDir(primary)) return primary
        if (tryMakeDir(fallback)) return fallback
        return null
    }

    /** Creates leaf directory only; parent must already exist. */
    private fun tryMakeDir(dir: String): Boolean {
        if (isDirectory(dir)) return true
        mkdirMode755(dir)
        return isDirectory(dir)
    }
}
