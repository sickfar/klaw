package io.github.klaw.cli.init

import kotlin.experimental.ExperimentalNativeApi
import kotlin.native.OsFamily
import kotlin.native.Platform

internal enum class KlawService(
    val dockerName: String,
    val systemdName: String,
) {
    ENGINE("klaw-engine", "klaw-engine"),
    GATEWAY("klaw-gateway", "klaw-gateway"),
}

@OptIn(ExperimentalNativeApi::class)
internal class ServiceManager(
    private val printer: (String) -> Unit,
    private val commandRunner: (String) -> Int,
    private val isDockerEnv: Boolean = isInsideDocker(),
    private val composeFile: String = "/app/docker-compose.yml",
    private val osFamily: OsFamily = Platform.osFamily,
) {
    fun start(service: KlawService): Boolean {
        printer("Starting ${service.dockerName}...")
        val cmd =
            when {
                isDockerEnv -> "docker compose -f '$composeFile' up -d ${service.dockerName}"
                osFamily == OsFamily.LINUX -> "systemctl --user start ${service.systemdName}"
                osFamily == OsFamily.MACOSX -> "launchctl start io.github.klaw.${service.systemdName}"
                else -> "systemctl --user start ${service.systemdName}"
            }
        return commandRunner(cmd) == 0
    }

    fun stop(service: KlawService): Boolean {
        printer("Stopping ${service.dockerName}...")
        val cmd =
            when {
                isDockerEnv -> "docker compose -f '$composeFile' stop ${service.dockerName}"
                osFamily == OsFamily.LINUX -> "systemctl --user stop ${service.systemdName}"
                osFamily == OsFamily.MACOSX -> "launchctl stop io.github.klaw.${service.systemdName}"
                else -> "systemctl --user stop ${service.systemdName}"
            }
        return commandRunner(cmd) == 0
    }

    fun restart(service: KlawService): Boolean {
        printer("Restarting ${service.dockerName}...")
        return when {
            isDockerEnv -> {
                val cmd = "docker compose -f '$composeFile' restart ${service.dockerName}"
                commandRunner(cmd) == 0
            }

            osFamily == OsFamily.LINUX -> {
                val cmd = "systemctl --user restart ${service.systemdName}"
                commandRunner(cmd) == 0
            }

            osFamily == OsFamily.MACOSX -> {
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

    fun stopAll(): Boolean {
        printer("Stopping all klaw services...")
        val cmd =
            when {
                isDockerEnv -> {
                    "docker compose -f '$composeFile' stop klaw-gateway klaw-engine"
                }

                osFamily == OsFamily.LINUX -> {
                    "systemctl --user stop klaw-gateway klaw-engine"
                }

                osFamily == OsFamily.MACOSX -> {
                    val stopGateway = commandRunner("launchctl stop io.github.klaw.klaw-gateway")
                    val stopEngine = commandRunner("launchctl stop io.github.klaw.klaw-engine")
                    return stopGateway == 0 && stopEngine == 0
                }

                else -> {
                    "systemctl --user stop klaw-gateway klaw-engine"
                }
            }
        return commandRunner(cmd) == 0
    }
}
