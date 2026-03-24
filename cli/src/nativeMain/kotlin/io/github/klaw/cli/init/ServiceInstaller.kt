package io.github.klaw.cli.init

import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.writeFileText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

@OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
internal class ServiceInstaller(
    private val outputDir: String = defaultOutputDir(),
    private val commandRunner: (String) -> Unit = { cmd -> platform.posix.system(cmd) },
) {
    /**
     * Writes service files and enables them, but does NOT start any services.
     * Used during init Phase 8 so that Phase 9 (engine start) can find the service files.
     */
    fun installWithoutStart(
        engineBin: String,
        gatewayBin: String,
        envFile: String,
    ) {
        doInstall(engineBin, gatewayBin, envFile, startServices = false)
    }

    fun install(
        engineBin: String,
        gatewayBin: String,
        envFile: String,
    ) {
        doInstall(engineBin, gatewayBin, envFile, startServices = true)
    }

    private fun doInstall(
        engineBin: String,
        gatewayBin: String,
        envFile: String,
        startServices: Boolean,
    ) {
        CliLogger.info { "installing services to $outputDir (start=$startServices)" }
        when (Platform.osFamily) {
            OsFamily.LINUX -> {
                writeSystemdUnits(engineBin, gatewayBin, envFile)
                commandRunner("systemctl --user daemon-reload")
                commandRunner("systemctl --user enable klaw-engine klaw-gateway")
                if (startServices) {
                    commandRunner("systemctl --user start klaw-gateway")
                }
            }

            OsFamily.MACOSX -> {
                writeLaunchdPlists(engineBin, gatewayBin)
                if (startServices) {
                    commandRunner("launchctl load -w $outputDir/io.github.klaw.engine.plist")
                    commandRunner("launchctl load -w $outputDir/io.github.klaw.gateway.plist")
                }
            }

            else -> {
                CliLogger.warn { "unsupported OS for service installation" }
            }
        }
    }

    fun writeSystemdUnits(
        engineBin: String,
        gatewayBin: String,
        envFile: String,
    ) {
        mkdirMode755(outputDir)
        writeFileText("$outputDir/klaw-engine.service", engineSystemdUnit(engineBin, envFile))
        writeFileText("$outputDir/klaw-gateway.service", gatewaySystemdUnit(gatewayBin, envFile))
    }

    fun writeLaunchdPlists(
        engineBin: String,
        gatewayBin: String,
    ) {
        mkdirMode755(outputDir)
        writeFileText("$outputDir/io.github.klaw.engine.plist", engineLaunchdPlist(engineBin))
        writeFileText("$outputDir/io.github.klaw.gateway.plist", gatewayLaunchdPlist(gatewayBin))
    }

    companion object {
        fun engineSystemdUnit(
            engineBin: String,
            envFile: String,
        ): String =
            """
[Unit]
Description=Klaw Engine (LLM orchestration)
After=network.target

[Service]
Type=simple
EnvironmentFile=$envFile
ExecStart=$engineBin
Restart=always
RestartSec=3

[Install]
WantedBy=default.target
            """.trimIndent()

        fun gatewaySystemdUnit(
            gatewayBin: String,
            envFile: String,
        ): String =
            """
[Unit]
Description=Klaw Gateway (Telegram)
After=klaw-engine.service
Requires=klaw-engine.service

[Service]
Type=simple
EnvironmentFile=$envFile
ExecStart=$gatewayBin
Restart=always
RestartSec=3

[Install]
WantedBy=default.target
            """.trimIndent()

        fun engineLaunchdPlist(engineBin: String): String =
            """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>io.github.klaw.engine</string>
  <key>ProgramArguments</key><array><string>$engineBin</string></array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>KLAW_LLM_API_KEY</key><string></string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict>
</plist>
            """.trimIndent()

        fun gatewayLaunchdPlist(gatewayBin: String): String =
            """
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN"
  "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>io.github.klaw.gateway</string>
  <key>ProgramArguments</key><array><string>$gatewayBin</string></array>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict>
</plist>
            """.trimIndent()

        @OptIn(ExperimentalNativeApi::class, ExperimentalForeignApi::class)
        internal fun defaultOutputDir(): String {
            val home = platform.posix.getenv("HOME")?.toKString() ?: "/root"
            return when (Platform.osFamily) {
                OsFamily.LINUX -> "$home/.config/systemd/user"
                OsFamily.MACOSX -> "$home/Library/LaunchAgents"
                else -> "/tmp/klaw-services"
            }
        }
    }
}
