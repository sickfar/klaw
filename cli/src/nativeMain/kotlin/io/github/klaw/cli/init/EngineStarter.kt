package io.github.klaw.cli.init

import io.github.klaw.cli.socket.buildTcpSockAddrBytes
import io.github.klaw.cli.socket.parseIpv4
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.common.paths.KlawPaths
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.AF_INET
import platform.posix.SOCK_STREAM
import platform.posix.close
import platform.posix.sockaddr
import platform.posix.socket
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform
import kotlin.time.Clock

@OptIn(ExperimentalForeignApi::class)
internal fun checkTcpPort(
    host: String,
    port: Int,
): Boolean {
    val fd = socket(AF_INET, SOCK_STREAM, 0)
    if (fd < 0) return false
    val ipBytes = parseIpv4(host)
    val addrBytes = buildTcpSockAddrBytes(port, ipBytes)
    val result =
        addrBytes.usePinned { pinned ->
            platform.posix.connect(fd, pinned.addressOf(0).reinterpret<sockaddr>(), addrBytes.size.convert())
        }
    close(fd)
    return result == 0
}

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
internal class EngineStarter(
    private val enginePort: Int = KlawPaths.enginePort,
    private val engineHost: String = KlawPaths.engineHost,
    private val portChecker: (String, Int) -> Boolean = ::checkTcpPort,
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

        CliLogger.debug { "polling for engine at $engineHost:$enginePort" }
        val deadline = Clock.System.now().toEpochMilliseconds() + timeoutMs
        while (Clock.System.now().toEpochMilliseconds() < deadline) {
            if (portChecker(engineHost, enginePort)) {
                CliLogger.info { "engine started, port responsive" }
                return true
            }
            onTick()
            sleepMs(pollIntervalMs)
        }
        CliLogger.warn { "engine start timed out after ${timeoutMs}ms" }
        return portChecker(engineHost, enginePort)
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
