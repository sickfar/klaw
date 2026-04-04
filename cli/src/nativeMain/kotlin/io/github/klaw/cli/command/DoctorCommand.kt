package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
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
    private val jsonOutput by option("--json", help = "Output as JSON").flag()
    private val deep by option("--deep", help = "Deep health probe").flag()
    private val nonInteractive by option("--non-interactive", help = "Run without prompts").flag()

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
        CliLogger.debug { "running doctor checks (nonInteractive=$nonInteractive)" }
        if (handleDumpSchema()) return

        val deployConfig = readDeployConf(configDir)
        val checks = mutableListOf<DoctorCheckResult>()

        checks +=
            checkConfigFile("gateway.json", "$configDir/gateway.json", gatewayJsonSchema()) {
                parseGatewayConfig(it)
            }
        checks +=
            checkConfigFile("engine.json", "$configDir/engine.json", engineJsonSchema()) {
                parseEngineConfig(it)
            }
        checks += checkEngine()
        checks += checkGatewayWebSocket(deployConfig)
        checks += checkModels()
        checks += checkWorkspace()
        checks += checkMcpConfig(deployConfig)

        if (deployConfig.mode != DeployMode.NATIVE) {
            checks += checkDocker()
        }

        checks += checkSkills()

        val deepResult = if (deep) runDeepProbe() else null

        val report =
            DoctorReport(
                deployMode = deployConfig.mode.configName,
                checks = checks,
                deep = deepResult,
            )
        val output =
            if (jsonOutput) {
                DoctorReportFormatter.toJson(report)
            } else {
                DoctorReportFormatter.toText(report)
            }
        echo(output)
    }

    private fun runDeepProbe(): DoctorDeepResult? =
        try {
            val response = requestFn("doctor_deep", emptyMap(), "default")
            CliLogger.debug { "deep probe response length: ${response.length}" }
            DoctorDeepResult(response)
        } catch (_: EngineNotRunningException) {
            CliLogger.debug { "deep probe skipped \u2014 engine not running" }
            null
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            CliLogger.warn { "deep probe error: ${e::class.simpleName}" }
            null
        }

    private fun checkSkills(): List<DoctorCheckResult> =
        try {
            requestFn("skills_validate", emptyMap(), "default")
            CliLogger.debug { "skills validation passed" }
            listOf(DoctorCheckResult("Skills", CheckStatus.OK, "valid"))
        } catch (_: EngineNotRunningException) {
            CliLogger.debug { "skills validation skipped \u2014 engine not running" }
            listOf(DoctorCheckResult("Skills", CheckStatus.WARN, "skipped (engine not running)"))
        } catch (
            @Suppress("TooGenericExceptionCaught")
            e: Exception,
        ) {
            CliLogger.warn { "skills validation error: ${e::class.simpleName}" }
            listOf(DoctorCheckResult("Skills", CheckStatus.FAIL, "error (${e::class.simpleName})"))
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

    private fun checkEngine(): List<DoctorCheckResult> =
        if (engineChecker()) {
            CliLogger.debug { "engine port responsive" }
            listOf(DoctorCheckResult("Engine", CheckStatus.OK, "running"))
        } else {
            CliLogger.warn { "engine not responding on ${KlawPaths.engineHost}:${KlawPaths.enginePort}" }
            listOf(
                DoctorCheckResult(
                    "Engine",
                    CheckStatus.FAIL,
                    "stopped (not responding on ${KlawPaths.engineHost}:${KlawPaths.enginePort})",
                ),
            )
        }

    private fun checkGatewayWebSocket(deployConfig: io.github.klaw.cli.init.DeployConfig): List<DoctorCheckResult> {
        val consoleConfig = readConsoleChatConfig(configDir)
        if (!consoleConfig.enabled) return emptyList()
        return if (checkTcpPort("127.0.0.1", consoleConfig.port)) {
            CliLogger.debug { "gateway WebSocket port responsive on ${consoleConfig.port}" }
            listOf(
                DoctorCheckResult("Gateway WebSocket", CheckStatus.OK, "reachable on port ${consoleConfig.port}"),
            )
        } else {
            CliLogger.warn { "gateway WebSocket not responding on port ${consoleConfig.port}" }
            val details =
                if (deployConfig.mode != DeployMode.NATIVE) {
                    listOf("Hint: check docker-compose.json for gateway port mapping")
                } else {
                    emptyList()
                }
            listOf(
                DoctorCheckResult(
                    "Gateway WebSocket",
                    CheckStatus.FAIL,
                    "not reachable on port ${consoleConfig.port}",
                    details,
                ),
            )
        }
    }

    private fun checkModels(): List<DoctorCheckResult> {
        val onnxFiles =
            if (fileExists(modelsDir) && isDirectory(modelsDir)) {
                listDirectory(modelsDir).filter { it.endsWith(".onnx") }
            } else {
                emptyList()
            }
        return if (onnxFiles.isNotEmpty()) {
            CliLogger.debug { "ONNX models found: ${onnxFiles.size}" }
            listOf(
                DoctorCheckResult("ONNX model", CheckStatus.OK, "${onnxFiles.size} .onnx file(s) found in $modelsDir"),
            )
        } else {
            CliLogger.warn { "no ONNX models in $modelsDir" }
            listOf(DoctorCheckResult("ONNX model", CheckStatus.FAIL, "no .onnx files in models directory ($modelsDir)"))
        }
    }

    private fun checkWorkspace(): List<DoctorCheckResult> =
        if (fileExists(workspaceDir) && isDirectory(workspaceDir)) {
            CliLogger.debug { "workspace found" }
            listOf(DoctorCheckResult("Workspace", CheckStatus.OK, "found"))
        } else {
            CliLogger.warn { "workspace missing at $workspaceDir" }
            listOf(DoctorCheckResult("Workspace", CheckStatus.FAIL, "missing ($workspaceDir)"))
        }

    private fun checkConfigFile(
        name: String,
        path: String,
        schema: JsonObject,
        parser: (String) -> Any,
    ): List<DoctorCheckResult> {
        if (!fileExists(path)) {
            return listOf(DoctorCheckResult(name, CheckStatus.FAIL, "missing ($path)"))
        }
        val content = readFileText(path)
        if (content == null) {
            return listOf(DoctorCheckResult(name, CheckStatus.FAIL, "unreadable ($path)"))
        }

        val element =
            try {
                klawJson.parseToJsonElement(content)
            } catch (e: SerializationException) {
                return listOf(DoctorCheckResult(name, CheckStatus.FAIL, "parse error (${e::class.simpleName})"))
            }

        val schemaErrors = validateConfig(schema, element)
        if (schemaErrors.isNotEmpty()) {
            return listOf(
                DoctorCheckResult(
                    name,
                    CheckStatus.FAIL,
                    "invalid",
                    schemaErrors.map { "${it.path}: ${it.message}" },
                ),
            )
        }

        return try {
            parser(content)
            listOf(DoctorCheckResult(name, CheckStatus.OK, "valid"))
        } catch (e: IllegalArgumentException) {
            listOf(DoctorCheckResult(name, CheckStatus.FAIL, "validation error (${e::class.simpleName})"))
        }
    }

    private fun checkMcpConfig(deployConfig: io.github.klaw.cli.init.DeployConfig): List<DoctorCheckResult> {
        val mcpFile = "$configDir/mcp.json"
        if (!fileExists(mcpFile)) return emptyList()
        val content = readFileText(mcpFile)
        if (content == null) {
            return listOf(DoctorCheckResult("mcp.json", CheckStatus.FAIL, "unreadable ($mcpFile)"))
        }
        val config =
            try {
                parseMcpConfig(content)
            } catch (e: SerializationException) {
                return listOf(DoctorCheckResult("mcp.json", CheckStatus.FAIL, "parse error (${e::class.simpleName})"))
            }
        if (config.servers.isEmpty()) {
            return listOf(DoctorCheckResult("mcp.json", CheckStatus.OK, "valid (no servers)"))
        }
        val results =
            mutableListOf(
                DoctorCheckResult("mcp.json", CheckStatus.OK, "valid (${config.servers.size} server(s))"),
            )
        for ((name, server) in config.servers) {
            results += checkMcpServer(name, server, deployConfig)
        }
        return results
    }

    private fun checkMcpServer(
        name: String,
        server: io.github.klaw.common.config.McpServerConfig,
        deployConfig: io.github.klaw.cli.init.DeployConfig,
    ): List<DoctorCheckResult> {
        if (!server.enabled) {
            return listOf(DoctorCheckResult(name, CheckStatus.SKIP, "disabled"))
        }
        return when (server.transport) {
            "stdio" -> checkMcpStdioServer(name, server, deployConfig)
            "http" -> checkMcpHttpServer(name, server)
            else -> listOf(DoctorCheckResult(name, CheckStatus.FAIL, "unknown transport '${server.transport}'"))
        }
    }

    private fun checkMcpStdioServer(
        name: String,
        server: io.github.klaw.common.config.McpServerConfig,
        deployConfig: io.github.klaw.cli.init.DeployConfig,
    ): List<DoctorCheckResult> {
        val cmd = server.command
        if (cmd == null) {
            return listOf(DoctorCheckResult(name, CheckStatus.FAIL, "stdio transport requires 'command'"))
        }
        if (deployConfig.mode == DeployMode.NATIVE) {
            return listOf(DoctorCheckResult(name, CheckStatus.OK, "stdio ($cmd)"))
        }
        return when {
            cmd == "docker" -> {
                listOf(DoctorCheckResult(name, CheckStatus.OK, "stdio via user docker command"))
            }

            STDIO_DOCKER_IMAGES.containsKey(cmd) -> {
                listOf(DoctorCheckResult(name, CheckStatus.OK, "stdio via ${STDIO_DOCKER_IMAGES[cmd]}"))
            }

            else -> {
                listOf(
                    DoctorCheckResult(
                        name,
                        CheckStatus.WARN,
                        "command '$cmd' has no known Docker image \u2014 " +
                            "will be skipped. Use HTTP transport or wrap in a docker command",
                    ),
                )
            }
        }
    }

    private fun checkMcpHttpServer(
        name: String,
        server: io.github.klaw.common.config.McpServerConfig,
    ): List<DoctorCheckResult> =
        if (server.url == null) {
            listOf(DoctorCheckResult(name, CheckStatus.FAIL, "http transport requires 'url'"))
        } else {
            listOf(DoctorCheckResult(name, CheckStatus.OK, "http (${server.url})"))
        }

    private fun checkDocker(): List<DoctorCheckResult> {
        val composeFile = "$configDir/docker-compose.json"
        val results = mutableListOf<DoctorCheckResult>()

        results +=
            checkConfigFile("docker-compose.json", composeFile, composeJsonSchema()) {
                parseComposeConfig(it)
            }

        val composeContent = readFileText(composeFile)
        if (composeContent != null) {
            try {
                val compose = parseComposeConfig(composeContent)
                val engineVolumes = compose.services["engine"]?.volumes ?: emptyList()
                if (engineVolumes.any { it.contains("/var/run/docker.sock") }) {
                    results += DoctorCheckResult("Docker socket", CheckStatus.OK, "mounted")
                } else {
                    results +=
                        DoctorCheckResult(
                            "Docker socket",
                            CheckStatus.FAIL,
                            "not mounted in engine service (required for sandbox_exec)",
                        )
                }
            } catch (_: Exception) {
                // Config already reported as invalid by checkConfigFile
            }
        }

        val output =
            commandOutput(
                "docker compose -f '$composeFile' ps --services --filter status=running",
            )
        if (output == null) {
            results += DoctorCheckResult("Docker", CheckStatus.FAIL, "unavailable (cannot query container status)")
            return results
        }

        val runningServices = output.lines().map { it.trim() }.filter { it.isNotEmpty() }
        results += checkContainerStatus("engine", runningServices)
        results += checkContainerStatus("gateway", runningServices)
        return results
    }

    private fun checkContainerStatus(
        service: String,
        runningServices: List<String>,
    ): DoctorCheckResult =
        if (service in runningServices) {
            DoctorCheckResult("Container $service", CheckStatus.OK, "running")
        } else {
            DoctorCheckResult("Container $service", CheckStatus.FAIL, "stopped")
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
