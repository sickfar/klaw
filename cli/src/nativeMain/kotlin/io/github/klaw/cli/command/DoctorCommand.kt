package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import io.github.klaw.cli.EngineRequest
import io.github.klaw.cli.chat.readConsoleChatConfig
import io.github.klaw.cli.init.DeployMode
import io.github.klaw.cli.init.checkTcpPort
import io.github.klaw.cli.init.readDeployConf
import io.github.klaw.cli.socket.EngineNotRunningException
import io.github.klaw.cli.util.CliLogger
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
import io.github.klaw.common.config.parseMcpConfig
import io.github.klaw.common.config.schema.composeJsonSchema
import io.github.klaw.common.config.schema.engineJsonSchema
import io.github.klaw.common.config.schema.gatewayJsonSchema
import io.github.klaw.common.config.schema.validateConfig
import io.github.klaw.common.paths.KlawPaths
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject

@Suppress("LongParameterList")
internal class DoctorCommand(
    private val configDir: String = KlawPaths.config,
    private val engineChecker: () -> Boolean = { checkTcpPort(KlawPaths.engineHost, KlawPaths.enginePort) },
    private val modelsDir: String = KlawPaths.models,
    private val workspaceDir: String = KlawPaths.workspace,
    private val commandOutput: (String) -> String? = ::runCommandOutput,
    private val commandRunner: (String) -> Int = { cmd -> platform.posix.system(cmd) },
    private val requestFn: EngineRequest,
) : CliktCommand(name = "doctor") {
    override val invokeWithoutSubcommand = true
    private val dumpSchema by option("--dump-schema")

    init {
        subcommands(
            DoctorFixCommand(
                configDir = configDir,
                workspaceDir = workspaceDir,
                engineChecker = engineChecker,
                commandRunner = commandRunner,
            ),
        )
    }

    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        CliLogger.debug { "running doctor checks" }
        if (handleDumpSchema()) return

        val deployConfig = readDeployConf(configDir)
        echo("  Deploy mode: ${deployConfig.mode.configName}")

        checkConfigFile("gateway.json", "$configDir/gateway.json", gatewayJsonSchema()) { parseGatewayConfig(it) }
        checkConfigFile("engine.json", "$configDir/engine.json", engineJsonSchema()) { parseEngineConfig(it) }
        checkEngine()
        checkGatewayWebSocket(deployConfig)
        checkModels()
        checkWorkspace()
        checkMcpConfig(deployConfig)

        if (deployConfig.mode != DeployMode.NATIVE) {
            checkDocker()
        }

        checkSkills()
    }

    private fun checkSkills() {
        try {
            requestFn("skills_validate", emptyMap())
            CliLogger.debug { "skills validation passed" }
            echo("\u2713 Skills: valid")
        } catch (_: EngineNotRunningException) {
            CliLogger.debug { "skills validation skipped \u2014 engine not running" }
            echo("\u26a0 Skills: skipped (engine not running)")
        }
    }

    private fun handleDumpSchema(): Boolean {
        val target = dumpSchema ?: return false
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
                    return true
                }
            }
        echo(klawPrettyJson.encodeToString(JsonObject.serializer(), schema))
        return true
    }

    private fun checkEngine() {
        if (engineChecker()) {
            CliLogger.debug { "engine port responsive" }
            echo("\u2713 Engine: running")
        } else {
            CliLogger.warn { "engine not responding on ${KlawPaths.engineHost}:${KlawPaths.enginePort}" }
            echo("\u2717 Engine: stopped (not responding on ${KlawPaths.engineHost}:${KlawPaths.enginePort})")
        }
    }

    private fun checkGatewayWebSocket(deployConfig: io.github.klaw.cli.init.DeployConfig) {
        val consoleConfig = readConsoleChatConfig(configDir)
        if (!consoleConfig.enabled) return
        if (checkTcpPort("127.0.0.1", consoleConfig.port)) {
            CliLogger.debug { "gateway WebSocket port responsive on ${consoleConfig.port}" }
            echo("\u2713 Gateway WebSocket: reachable on port ${consoleConfig.port}")
        } else {
            CliLogger.warn { "gateway WebSocket not responding on port ${consoleConfig.port}" }
            echo("\u2717 Gateway WebSocket: not reachable on port ${consoleConfig.port}")
            if (deployConfig.mode != DeployMode.NATIVE) {
                echo("  Hint: check docker-compose.json for gateway port mapping")
            }
        }
    }

    private fun checkModels() {
        val onnxFiles =
            if (fileExists(modelsDir) && isDirectory(modelsDir)) {
                listDirectory(modelsDir).filter { it.endsWith(".onnx") }
            } else {
                emptyList()
            }
        if (onnxFiles.isNotEmpty()) {
            CliLogger.debug { "ONNX models found: ${onnxFiles.size}" }
            echo("\u2713 ONNX model: ${onnxFiles.size} .onnx file(s) found in $modelsDir")
        } else {
            CliLogger.warn { "no ONNX models in $modelsDir" }
            echo("\u2717 ONNX: no .onnx files in models directory ($modelsDir)")
        }
    }

    private fun checkWorkspace() {
        if (fileExists(workspaceDir) && isDirectory(workspaceDir)) {
            CliLogger.debug { "workspace found" }
            echo("\u2713 Workspace: found")
        } else {
            CliLogger.warn { "workspace missing at $workspaceDir" }
            echo("\u2717 Workspace: missing ($workspaceDir)")
        }
    }

    private fun checkConfigFile(
        name: String,
        path: String,
        schema: JsonObject,
        parser: (String) -> Any,
    ) {
        if (!fileExists(path)) {
            echo("\u2717 $name: missing ($path)")
            return
        }
        val content = readFileText(path)
        if (content == null) {
            echo("\u2717 $name: unreadable ($path)")
            return
        }

        // Step 1: Parse raw JSON
        val element =
            try {
                klawJson.parseToJsonElement(content)
            } catch (e: SerializationException) {
                echo("\u2717 $name: parse error (${e::class.simpleName})")
                return
            }

        // Step 2: Schema validation
        val schemaErrors = validateConfig(schema, element)
        if (schemaErrors.isNotEmpty()) {
            echo("\u2717 $name: invalid")
            schemaErrors.forEach { error ->
                echo("  - ${error.path}: ${error.message}")
            }
            return
        }

        // Step 3: Full deserialization (catches init block require() failures)
        try {
            parser(content)
            echo("\u2713 $name: valid")
        } catch (e: IllegalArgumentException) {
            // Catches SerializationException (extends IllegalArgumentException) and require() failures
            echo("\u2717 $name: validation error (${e::class.simpleName})")
        }
    }

    private fun checkMcpConfig(deployConfig: io.github.klaw.cli.init.DeployConfig) {
        val mcpFile = "$configDir/mcp.json"
        if (!fileExists(mcpFile)) return
        val content = readFileText(mcpFile)
        if (content == null) {
            echo("\u2717 mcp.json: unreadable ($mcpFile)")
            return
        }
        val config =
            try {
                parseMcpConfig(content)
            } catch (e: SerializationException) {
                echo("\u2717 mcp.json: parse error (${e::class.simpleName})")
                return
            }
        if (config.servers.isEmpty()) {
            echo("\u2713 mcp.json: valid (no servers)")
            return
        }
        echo("\u2713 mcp.json: valid (${config.servers.size} server(s))")
        for ((name, server) in config.servers) {
            checkMcpServer(name, server, deployConfig)
        }
    }

    private fun checkMcpServer(
        name: String,
        server: io.github.klaw.common.config.McpServerConfig,
        deployConfig: io.github.klaw.cli.init.DeployConfig,
    ) {
        if (!server.enabled) {
            echo("  - $name: disabled")
            return
        }
        when (server.transport) {
            "stdio" -> checkMcpStdioServer(name, server, deployConfig)
            "http" -> checkMcpHttpServer(name, server)
            else -> echo("  \u2717 $name: unknown transport '${server.transport}'")
        }
    }

    private fun checkMcpStdioServer(
        name: String,
        server: io.github.klaw.common.config.McpServerConfig,
        deployConfig: io.github.klaw.cli.init.DeployConfig,
    ) {
        val cmd = server.command
        if (cmd == null) {
            echo("  \u2717 $name: stdio transport requires 'command'")
            return
        }
        if (deployConfig.mode == DeployMode.NATIVE) {
            echo("  \u2713 $name: stdio ($cmd)")
            return
        }
        when {
            cmd == "docker" -> {
                echo("  \u2713 $name: stdio via user docker command")
            }

            STDIO_DOCKER_IMAGES.containsKey(cmd) -> {
                echo("  \u2713 $name: stdio via ${STDIO_DOCKER_IMAGES[cmd]}")
            }

            else -> {
                echo(
                    "  \u26a0 $name: command '$cmd' has no known Docker image — " +
                        "will be skipped. Use HTTP transport or wrap in a docker command",
                )
            }
        }
    }

    private fun checkMcpHttpServer(
        name: String,
        server: io.github.klaw.common.config.McpServerConfig,
    ) {
        if (server.url == null) {
            echo("  \u2717 $name: http transport requires 'url'")
        } else {
            echo("  \u2713 $name: http (${server.url})")
        }
    }

    private fun checkDocker() {
        val composeFile = "$configDir/docker-compose.json"

        checkConfigFile("docker-compose.json", composeFile, composeJsonSchema()) { parseComposeConfig(it) }

        // Docker socket mount check
        val composeContent = readFileText(composeFile)
        if (composeContent != null) {
            try {
                val compose = parseComposeConfig(composeContent)
                val engineVolumes = compose.services["engine"]?.volumes ?: emptyList()
                if (engineVolumes.any { it.contains("/var/run/docker.sock") }) {
                    echo("\u2713 Docker socket: mounted")
                } else {
                    echo("\u2717 Docker socket: not mounted in engine service (required for sandbox_exec)")
                }
            } catch (_: Exception) {
                // Config already reported as invalid by checkConfigFile
            }
        }

        // Container status
        val output =
            commandOutput(
                "docker compose -f '$composeFile' ps --services --filter status=running",
            )
        if (output == null) {
            echo("\u2717 Docker: unavailable (cannot query container status)")
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
            echo("\u2713 Container $service: running")
        } else {
            echo("\u2717 Container $service: stopped")
        }
    }

    companion object {
        private val STDIO_DOCKER_IMAGES =
            mapOf(
                "npx" to "node:22-alpine",
                "node" to "node:22-alpine",
                "uvx" to "python:3.12-slim",
                "python" to "python:3.12-slim",
                "python3" to "python:3.12-slim",
            )
    }
}
