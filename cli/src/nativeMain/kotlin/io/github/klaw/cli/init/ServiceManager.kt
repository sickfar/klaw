package io.github.klaw.cli.init

import io.github.klaw.cli.util.CliLogger
import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

internal enum class KlawService(
    val dockerName: String,
    val systemdName: String,
) {
    ENGINE("engine", "klaw-engine"),
    GATEWAY("gateway", "klaw-gateway"),
}

@OptIn(ExperimentalNativeApi::class)
internal class ServiceManager(
    private val printer: (String) -> Unit,
    private val commandRunner: (String) -> Int,
    private val deployMode: DeployMode = DeployMode.NATIVE,
    private val composeFile: String = "/app/docker-compose.json",
    private val osFamily: OsFamily = Platform.osFamily,
) {
    fun start(service: KlawService): Boolean {
        CliLogger.debug { "service start ${service.dockerName} mode=$deployMode" }
        printer("Starting ${service.dockerName}...")
        val cmd =
            when (deployMode) {
                DeployMode.DOCKER, DeployMode.HYBRID -> {
                    "docker compose -f '$composeFile' up -d ${service.dockerName}"
                }

                DeployMode.NATIVE -> {
                    when (osFamily) {
                        OsFamily.MACOSX -> "launchctl start io.github.klaw.${service.systemdName}"
                        else -> "systemctl --user start ${service.systemdName}"
                    }
                }
            }
        return commandRunner(cmd) == 0
    }

    fun stop(service: KlawService): Boolean {
        CliLogger.debug { "service stop ${service.dockerName} mode=$deployMode" }
        printer("Stopping ${service.dockerName}...")
        val cmd =
            when (deployMode) {
                DeployMode.DOCKER, DeployMode.HYBRID -> {
                    "docker compose -f '$composeFile' stop ${service.dockerName}"
                }

                DeployMode.NATIVE -> {
                    when (osFamily) {
                        OsFamily.MACOSX -> "launchctl stop io.github.klaw.${service.systemdName}"
                        else -> "systemctl --user stop ${service.systemdName}"
                    }
                }
            }
        return commandRunner(cmd) == 0
    }

    fun restart(service: KlawService): Boolean {
        CliLogger.debug { "service restart ${service.dockerName} mode=$deployMode" }
        printer("Restarting ${service.dockerName}...")
        return when (deployMode) {
            DeployMode.DOCKER, DeployMode.HYBRID -> {
                val cmd = "docker compose -f '$composeFile' restart ${service.dockerName}"
                commandRunner(cmd) == 0
            }

            DeployMode.NATIVE -> {
                when (osFamily) {
                    OsFamily.MACOSX -> {
                        val stopResult = commandRunner("launchctl stop io.github.klaw.${service.systemdName}")
                        val startResult = commandRunner("launchctl start io.github.klaw.${service.systemdName}")
                        stopResult == 0 && startResult == 0
                    }

                    else -> {
                        val cmd = "systemctl --user restart ${service.systemdName}"
                        commandRunner(cmd) == 0
                    }
                }
            }
        }
    }

    fun stopAll(): Boolean {
        CliLogger.debug { "stopping all services mode=$deployMode" }
        printer("Stopping all klaw services...")
        return when (deployMode) {
            DeployMode.DOCKER, DeployMode.HYBRID -> {
                val cmd = "docker compose -f '$composeFile' stop gateway engine"
                commandRunner(cmd) == 0
            }

            DeployMode.NATIVE -> {
                when (osFamily) {
                    OsFamily.MACOSX -> {
                        val stopGateway = commandRunner("launchctl stop io.github.klaw.klaw-gateway")
                        val stopEngine = commandRunner("launchctl stop io.github.klaw.klaw-engine")
                        stopGateway == 0 && stopEngine == 0
                    }

                    else -> {
                        val cmd = "systemctl --user stop klaw-gateway klaw-engine"
                        commandRunner(cmd) == 0
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalNativeApi::class)
internal fun createServiceManager(
    printer: (String) -> Unit,
    commandRunner: (String) -> Int,
    configDir: String,
): ServiceManager {
    val deployConfig = readDeployConf(configDir)
    val composeFile =
        when (deployConfig.mode) {
            DeployMode.DOCKER -> "/app/docker-compose.json"
            DeployMode.HYBRID -> "$configDir/docker-compose.json"
            DeployMode.NATIVE -> ""
        }
    return ServiceManager(printer, commandRunner, deployConfig.mode, composeFile)
}
