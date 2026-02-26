package io.github.klaw.cli.init

import io.github.klaw.cli.util.fileExists
import io.github.klaw.common.paths.KlawPaths
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.time.Clock

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
internal class EngineStarter(
    private val engineSocketPath: String = KlawPaths.engineSocket,
    private val commandRunner: (String) -> Int = { cmd -> platform.posix.system(cmd) },
    private val pollIntervalMs: Long = 500L,
    private val timeoutMs: Long = 30_000L,
    private val onTick: () -> Unit = {},
    /** Override the start command. When null, OS-appropriate default is used (systemd/launchd). */
    private val startCommand: String? = null,
) {
    fun startAndWait(): Boolean {
        val startCmd =
            startCommand
                ?: when (Platform.osFamily) {
                    OsFamily.LINUX -> {
                        "systemctl --user start klaw-engine"
                    }

                    OsFamily.MACOSX -> {
                        val home = platform.posix.getenv("HOME")?.toKString() ?: "/tmp"
                        "launchctl load -w $home/Library/LaunchAgents/io.github.klaw.engine.plist"
                    }

                    else -> {
                        "echo 'Please start the engine manually'"
                    }
                }
        commandRunner(startCmd)

        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            if (fileExists(engineSocketPath)) return true
            onTick()
            sleepMs(pollIntervalMs)
        }
        return fileExists(engineSocketPath)
    }

    private fun sleepMs(ms: Long) {
        // usleep takes microseconds; max is 1_000_000 per POSIX so handle large values
        val microseconds = ms * 1_000L
        if (microseconds > 999_999) {
            platform.posix.sleep((ms / 1000).toUInt())
        } else {
            platform.posix.usleep(microseconds.toUInt())
        }
    }
}
