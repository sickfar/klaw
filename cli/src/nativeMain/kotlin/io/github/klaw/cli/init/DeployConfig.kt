package io.github.klaw.cli.init

import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import platform.posix.rename

internal enum class DeployMode(
    val configName: String,
) {
    NATIVE("native"),
    HYBRID("hybrid"),
    DOCKER("docker"),
}

internal data class DeployConfig(
    val mode: DeployMode = DeployMode.NATIVE,
    val dockerTag: String = "latest",
)

internal fun readDeployConf(configDir: String): DeployConfig {
    val content = readFileText("$configDir/deploy.conf") ?: return DeployConfig()
    val map = mutableMapOf<String, String>()
    for (line in content.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val eqIdx = trimmed.indexOf('=')
        if (eqIdx < 0) continue
        val key = trimmed.substring(0, eqIdx).trim()
        val value = trimmed.substring(eqIdx + 1).trim()
        map[key] = value
    }
    val mode = DeployMode.entries.firstOrNull { it.configName == map["mode"] } ?: DeployMode.NATIVE
    val dockerTag = map["docker_tag"]?.ifBlank { "latest" } ?: "latest"
    return DeployConfig(mode, dockerTag)
}

internal fun writeDeployConf(
    configDir: String,
    config: DeployConfig,
) {
    val content = "mode=${config.mode.configName}\ndocker_tag=${config.dockerTag}\n"
    val tmpPath = "$configDir/deploy.conf.tmp"
    val finalPath = "$configDir/deploy.conf"
    writeFileText(tmpPath, content)
    chmodReadWrite(tmpPath)
    rename(tmpPath, finalPath)
}
