package io.github.klaw.cli.configure

import io.github.klaw.cli.init.EnvReader
import io.github.klaw.cli.init.EnvWriter
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.encodeEngineConfigMinimal
import io.github.klaw.common.config.encodeGatewayConfigMinimal
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig

internal class ConfigureRunner(
    private val configDir: String,
    private val sections: List<ConfigSection>,
    private val printer: (String) -> Unit,
    private val handlerFactory: (ConfigSection) -> SectionHandler,
) {
    fun run() {
        val enginePath = "$configDir/engine.json"
        val gatewayPath = "$configDir/gateway.json"
        val envPath = "$configDir/.env"

        if (!fileExists(enginePath)) {
            printer("Not initialized. Run `klaw init` first.")
            return
        }
        if (!fileExists(gatewayPath)) {
            printer("gateway.json not found in $configDir. Run `klaw init` first.")
            return
        }

        val engineJson =
            readFileText(enginePath) ?: run {
                printer("Cannot read $enginePath. Check file permissions.")
                return
            }
        val gatewayJson =
            readFileText(gatewayPath) ?: run {
                printer("Cannot read $gatewayPath. Check file permissions.")
                return
            }

        val state = loadConfigState(engineJson, gatewayJson, envPath) ?: return

        var anyChanged = false
        for (section in sections) {
            CliLogger.debug { "running section: ${section.cliName}" }
            val handler = handlerFactory(section)
            val changed = handler.run(state)
            if (changed) anyChanged = true
        }

        if (anyChanged) {
            writeFileText(enginePath, encodeEngineConfigMinimal(state.engineConfig))
            writeFileText(gatewayPath, encodeGatewayConfigMinimal(state.gatewayConfig))
            EnvWriter.write(envPath, state.envVars)
            printer("Configuration updated. Restart services to apply: klaw service restart")
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun loadConfigState(
        engineJson: String,
        gatewayJson: String,
        envPath: String,
    ): ConfigState? {
        val engineConfig =
            try {
                parseEngineConfig(engineJson)
            } catch (e: Exception) {
                CliLogger.error { "failed to parse engine.json: ${e::class.simpleName}" }
                printer("engine.json is corrupted. Fix it manually or re-run `klaw init --force`.")
                return null
            }
        val gatewayConfig =
            try {
                parseGatewayConfig(gatewayJson)
            } catch (e: Exception) {
                CliLogger.error { "failed to parse gateway.json: ${e::class.simpleName}" }
                printer("gateway.json is corrupted. Fix it manually or re-run `klaw init --force`.")
                return null
            }
        val envVars = EnvReader.read(envPath).toMutableMap()
        return ConfigState(engineConfig, gatewayConfig, envVars)
    }
}
