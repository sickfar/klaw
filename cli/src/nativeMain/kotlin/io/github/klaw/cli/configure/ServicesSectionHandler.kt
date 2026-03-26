package io.github.klaw.cli.configure

import io.github.klaw.cli.init.DeployConfig
import io.github.klaw.cli.init.DeployMode
import io.github.klaw.cli.init.readDeployConf
import io.github.klaw.cli.util.CliLogger
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

internal class ServicesSectionHandler(
    private val readLine: () -> String?,
    private val printer: (String) -> Unit,
    private val commandRunner: (String) -> Int,
    private val configDir: String,
) : SectionHandler {
    override val section: ConfigSection = ConfigSection.SERVICES

    override fun run(state: ConfigState): Boolean {
        val deploy = readDeployConf(configDir)

        printer("\n── Services ──")
        printer("Deploy mode: ${deploy.mode.configName}")

        printer("Restart services now? [y/N]:")
        val input = readLine() ?: return false
        if (!input.lowercase().startsWith("y")) return false

        val cmd = buildRestartCommand(deploy)
        if (cmd != null) {
            printer("Restarting services...")
            CliLogger.debug { "running: $cmd" }
            val exitCode = commandRunner(cmd)
            if (exitCode == 0) {
                printer("Services restarted.")
            } else {
                printer("Restart failed (exit code $exitCode).")
            }
        } else {
            printer("No service manager detected. Restart services manually.")
        }

        // Services handler does not modify config state
        return false
    }

    private fun buildRestartCommand(deploy: DeployConfig): String? =
        when (deploy.mode) {
            DeployMode.HYBRID, DeployMode.DOCKER -> {
                val composePath = "$configDir/docker-compose.json"
                "docker compose -f '$composePath' restart"
            }

            DeployMode.NATIVE -> {
                when {
                    isLinux() -> "systemctl --user restart klaw-engine klaw-gateway"
                    isMacos() -> buildMacosRestartCommand()
                    else -> null
                }
            }
        }

    private fun buildMacosRestartCommand(): String =
        "launchctl kickstart -k gui/\$(id -u)/io.github.klaw.engine && " +
            "launchctl kickstart -k gui/\$(id -u)/io.github.klaw.gateway"
}

@OptIn(ExperimentalNativeApi::class)
private fun isLinux(): Boolean = Platform.osFamily == OsFamily.LINUX

@OptIn(ExperimentalNativeApi::class)
private fun isMacos(): Boolean = Platform.osFamily == OsFamily.MACOSX
