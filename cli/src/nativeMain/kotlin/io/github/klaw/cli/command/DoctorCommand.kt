package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.init.DeployMode
import io.github.klaw.cli.init.readDeployConf
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.isDirectory
import io.github.klaw.cli.util.listDirectory
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.runCommandOutput
import io.github.klaw.common.config.klawJson
import io.github.klaw.common.config.klawPrettyJson
import io.github.klaw.common.config.parseComposeConfig
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.config.parseGatewayConfig
import io.github.klaw.common.config.schema.composeJsonSchema
import io.github.klaw.common.config.schema.engineJsonSchema
import io.github.klaw.common.config.schema.gatewayJsonSchema
import io.github.klaw.common.config.schema.validateConfig
import io.github.klaw.common.paths.KlawPaths
import kotlinx.serialization.json.JsonObject

@Suppress("LongParameterList")
internal class DoctorCommand(
    private val configDir: String = KlawPaths.config,
    private val engineSocketPath: String = KlawPaths.engineSocket,
    private val modelsDir: String = KlawPaths.models,
    private val workspaceDir: String = KlawPaths.workspace,
    private val commandOutput: (String) -> String? = ::runCommandOutput,
) : CliktCommand(name = "doctor") {
    private val dumpSchema by option("--dump-schema")

    override fun run() {
        // Handle --dump-schema early exit
        dumpSchema?.let { target ->
            val schema =
                when (target) {
                    "engine" -> {
                        engineJsonSchema()
                    }

                    "gateway" -> {
                        gatewayJsonSchema()
                    }

                    "compose" -> {
                        composeJsonSchema()
                    }

                    else -> {
                        echo("Unknown schema target: $target (use 'engine', 'gateway', or 'compose')")
                        return
                    }
                }
            echo(klawPrettyJson.encodeToString(JsonObject.serializer(), schema))
            return
        }

        val deployConfig = readDeployConf(configDir)
        echo("  Deploy mode: ${deployConfig.mode.configName}")

        val gatewayJson = "$configDir/gateway.json"
        val engineJson = "$configDir/engine.json"

        // Config files — schema validation then deserialization
        checkConfigFile("gateway.json", gatewayJson, gatewayJsonSchema()) { parseGatewayConfig(it) }
        checkConfigFile("engine.json", engineJson, engineJsonSchema()) { parseEngineConfig(it) }

        // Engine socket
        if (fileExists(engineSocketPath)) {
            echo("✓ Engine: running")
        } else {
            echo("✗ Engine: stopped (socket not found at $engineSocketPath)")
        }

        // ONNX models
        val onnxFiles =
            if (fileExists(modelsDir) && isDirectory(modelsDir)) {
                listDirectory(modelsDir).filter { it.endsWith(".onnx") }
            } else {
                emptyList()
            }
        if (onnxFiles.isNotEmpty()) {
            echo("✓ ONNX model: ${onnxFiles.size} .onnx file(s) found in $modelsDir")
        } else {
            echo("✗ ONNX: no .onnx files in models directory ($modelsDir)")
        }

        // Workspace
        if (fileExists(workspaceDir) && isDirectory(workspaceDir)) {
            echo("✓ Workspace: found")
        } else {
            echo("✗ Workspace: missing ($workspaceDir)")
        }

        // Docker checks (hybrid/docker only)
        if (deployConfig.mode != DeployMode.NATIVE) {
            checkDocker()
        }
    }

    private fun checkConfigFile(
        name: String,
        path: String,
        schema: JsonObject,
        parser: (String) -> Any,
    ) {
        if (!fileExists(path)) {
            echo("✗ $name: missing ($path)")
            return
        }
        val content = readFileText(path)
        if (content == null) {
            echo("✗ $name: unreadable ($path)")
            return
        }

        // Step 1: Parse raw JSON
        val element =
            try {
                klawJson.parseToJsonElement(content)
            } catch (e: Exception) {
                echo("✗ $name: parse error (${e::class.simpleName})")
                return
            }

        // Step 2: Schema validation
        val schemaErrors = validateConfig(schema, element)
        if (schemaErrors.isNotEmpty()) {
            echo("✗ $name: invalid")
            schemaErrors.forEach { error ->
                echo("  - ${error.path}: ${error.message}")
            }
            return
        }

        // Step 3: Full deserialization (catches init block require() failures)
        try {
            parser(content)
            echo("✓ $name: valid")
        } catch (e: Exception) {
            echo("✗ $name: validation error (${e::class.simpleName})")
        }
    }

    private fun checkDocker() {
        val composeFile = "$configDir/docker-compose.json"

        checkConfigFile("docker-compose.json", composeFile, composeJsonSchema()) { parseComposeConfig(it) }

        // Container status
        val output =
            commandOutput(
                "docker compose -f '$composeFile' ps --services --filter status=running",
            )
        if (output == null) {
            echo("✗ Docker: unavailable (cannot query container status)")
            return
        }

        val runningServices = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        checkContainerStatus("engine", runningServices)
        checkContainerStatus("gateway", runningServices)
    }

    private fun checkContainerStatus(
        service: String,
        runningServices: List<String>,
    ) {
        if (service in runningServices) {
            echo("✓ Container $service: running")
        } else {
            echo("✗ Container $service: stopped")
        }
    }
}
