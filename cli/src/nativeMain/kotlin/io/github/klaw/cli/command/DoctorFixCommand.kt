package io.github.klaw.cli.command

import com.github.ajalt.clikt.core.CliktCommand
import io.github.klaw.cli.init.DeployMode
import io.github.klaw.cli.init.WorkspaceInitializer
import io.github.klaw.cli.init.readDeployConf
import io.github.klaw.cli.util.CliLogger
import io.github.klaw.cli.util.fileExists
import io.github.klaw.cli.util.readFileText
import io.github.klaw.cli.util.writeFileText
import io.github.klaw.common.config.klawJson
import io.github.klaw.common.config.klawPrettyJson
import io.github.klaw.common.config.schema.SanitizeResult
import io.github.klaw.common.config.schema.composeJsonSchema
import io.github.klaw.common.config.schema.engineJsonSchema
import io.github.klaw.common.config.schema.gatewayJsonSchema
import io.github.klaw.common.config.schema.sanitizeConfig
import io.github.klaw.common.paths.KlawPaths
import kotlinx.serialization.json.JsonObject

@Suppress("LongParameterList")
internal class DoctorFixCommand(
    private val configDir: String = KlawPaths.config,
    private val workspaceDir: String = KlawPaths.workspace,
    private val dataDir: String = KlawPaths.data,
    private val stateDir: String = KlawPaths.state,
    private val cacheDir: String = KlawPaths.cache,
    private val conversationsDir: String = KlawPaths.conversations,
    private val memoryDir: String = KlawPaths.memory,
    private val skillsDir: String = KlawPaths.skills,
    private val modelsDir: String = KlawPaths.models,
    private val commandRunner: (String) -> Int = { cmd -> platform.posix.system(cmd) },
    private val engineChecker: () -> Boolean = { false },
) : CliktCommand(name = "fix") {
    override fun run() {
        CliLogger.debug { "running doctor fix" }

        // Step 1: Missing directories
        fixDirectories()

        // Step 2: Config sanitization
        fixConfig("engine.json", "$configDir/engine.json", engineJsonSchema())
        fixConfig("gateway.json", "$configDir/gateway.json", gatewayJsonSchema())

        // Step 3: Docker compose (hybrid/docker only)
        val deployConfig = readDeployConf(configDir)
        if (deployConfig.mode != DeployMode.NATIVE) {
            fixConfig("docker-compose.json", "$configDir/docker-compose.json", composeJsonSchema())
            fixDockerCompose("$configDir/docker-compose.json")
        }

        // Step 4: Stopped services
        if (!engineChecker()) {
            echo("Starting engine...")
            val result = commandRunner("klaw engine start")
            if (result == 0) {
                echo("Fixed: engine started")
            } else {
                echo("Skipped: could not start engine (exit code $result)")
            }
        } else {
            echo("Skipped: engine already running")
        }
    }

    private fun fixDirectories() {
        val initializer =
            WorkspaceInitializer(
                configDir = configDir,
                dataDir = dataDir,
                stateDir = stateDir,
                cacheDir = cacheDir,
                workspaceDir = workspaceDir,
                conversationsDir = conversationsDir,
                memoryDir = memoryDir,
                skillsDir = skillsDir,
                modelsDir = modelsDir,
            )

        val allDirs =
            listOf(
                configDir,
                dataDir,
                stateDir,
                cacheDir,
                workspaceDir,
                conversationsDir,
                memoryDir,
                skillsDir,
                modelsDir,
            )
        val dirsBefore = allDirs.filter { !fileExists(it) }

        initializer.initialize()

        if (dirsBefore.isEmpty()) {
            echo("Skipped: all directories already exist")
        } else {
            dirsBefore.forEach { echo("Fixed: created directory $it") }
        }
    }

    private fun fixConfig(
        name: String,
        path: String,
        schema: JsonObject,
    ) {
        if (!fileExists(path)) {
            echo("Skipped: $name does not exist (use 'klaw init' to generate)")
            return
        }

        val content = readFileText(path)
        if (content == null) {
            echo("Skipped: $name unreadable")
            return
        }

        val element =
            try {
                klawJson.parseToJsonElement(content)
            } catch (_: Exception) {
                echo("Skipped: $name has invalid JSON (fix manually)")
                return
            }

        val result: SanitizeResult = sanitizeConfig(schema, element)
        if (result.removedPaths.isEmpty()) {
            echo("Skipped: $name has no unknown keys")
            return
        }

        val sanitized = klawPrettyJson.encodeToString(JsonObject.serializer(), result.sanitized as JsonObject)
        writeFileText(path, sanitized)
        result.removedPaths.forEach { echo("Fixed: removed $it from $name") }
    }

    private fun fixDockerCompose(path: String) {
        if (!fileExists(path)) return

        val content = readFileText(path) ?: return
        val element =
            try {
                klawJson.parseToJsonElement(content) as? JsonObject ?: return
            } catch (_: Exception) {
                return
            }

        var compose = element
        var changed = false

        // Check docker.sock mount in engine volumes
        val services = compose["services"] as? JsonObject ?: return
        val engine = services["engine"] as? JsonObject
        if (engine != null) {
            val volumes = engine["volumes"] as? kotlinx.serialization.json.JsonArray
            val hasDockerSock = volumes?.any { it.toString().contains("/var/run/docker.sock") } == true
            if (!hasDockerSock) {
                compose = addDockerSockMount(compose)
                changed = true
                echo("Fixed: added docker.sock mount to engine volumes")
            }

            // Check KLAW_ENGINE_BIND=0.0.0.0
            val env = engine["environment"] as? JsonObject
            val hasBind = env?.get("KLAW_ENGINE_BIND")?.toString()?.contains("0.0.0.0") == true
            if (!hasBind) {
                compose = addEngineEnv(compose, "KLAW_ENGINE_BIND", "0.0.0.0")
                changed = true
                echo("Fixed: added KLAW_ENGINE_BIND=0.0.0.0 to engine environment")
            }

            // Check port mapping 127.0.0.1:7470:7470
            val ports = engine["ports"] as? kotlinx.serialization.json.JsonArray
            val hasPort = ports?.any { it.toString().contains("7470:7470") } == true
            if (!hasPort) {
                compose = addEnginePort(compose)
                changed = true
                echo("Fixed: added 127.0.0.1:7470:7470 port mapping to engine")
            }
        }

        val gateway = services["gateway"] as? JsonObject
        if (gateway != null) {
            val env = gateway["environment"] as? JsonObject
            val hasHost = env?.get("KLAW_ENGINE_HOST")?.toString()?.contains("engine") == true
            if (!hasHost) {
                compose = addServiceEnv(compose, "gateway", "KLAW_ENGINE_HOST", "engine")
                changed = true
                echo("Fixed: added KLAW_ENGINE_HOST=engine to gateway environment")
            }
        }

        if (changed) {
            val sanitized = klawPrettyJson.encodeToString(JsonObject.serializer(), compose)
            writeFileText(path, sanitized)
        } else {
            echo("Skipped: docker-compose.json structure is correct")
        }
    }

    private fun addDockerSockMount(compose: JsonObject): JsonObject =
        modifyService(compose, "engine") { engine ->
            val volumes =
                (engine["volumes"] as? kotlinx.serialization.json.JsonArray)?.toMutableList()
                    ?: mutableListOf()
            volumes.add(kotlinx.serialization.json.JsonPrimitive("/var/run/docker.sock:/var/run/docker.sock"))
            kotlinx.serialization.json.buildJsonObject {
                for ((k, v) in engine) {
                    if (k == "volumes") {
                        put(
                            "volumes",
                            kotlinx.serialization.json.buildJsonArray { volumes.forEach { add(it) } },
                        )
                    } else {
                        put(k, v)
                    }
                }
                if ("volumes" !in engine) {
                    put(
                        "volumes",
                        kotlinx.serialization.json.buildJsonArray { volumes.forEach { add(it) } },
                    )
                }
            }
        }

    private fun addEnginePort(compose: JsonObject): JsonObject =
        modifyService(compose, "engine") { engine ->
            val ports =
                (engine["ports"] as? kotlinx.serialization.json.JsonArray)?.toMutableList()
                    ?: mutableListOf()
            ports.add(kotlinx.serialization.json.JsonPrimitive("127.0.0.1:7470:7470"))
            kotlinx.serialization.json.buildJsonObject {
                for ((k, v) in engine) {
                    if (k == "ports") {
                        put(
                            "ports",
                            kotlinx.serialization.json.buildJsonArray { ports.forEach { add(it) } },
                        )
                    } else {
                        put(k, v)
                    }
                }
                if ("ports" !in engine) {
                    put(
                        "ports",
                        kotlinx.serialization.json.buildJsonArray { ports.forEach { add(it) } },
                    )
                }
            }
        }

    private fun addEngineEnv(
        compose: JsonObject,
        key: String,
        value: String,
    ): JsonObject = addServiceEnv(compose, "engine", key, value)

    private fun addServiceEnv(
        compose: JsonObject,
        serviceName: String,
        key: String,
        value: String,
    ): JsonObject =
        modifyService(compose, serviceName) { service ->
            val env = (service["environment"] as? JsonObject)?.toMutableMap() ?: mutableMapOf()
            env[key] = kotlinx.serialization.json.JsonPrimitive(value)
            kotlinx.serialization.json.buildJsonObject {
                for ((k, v) in service) {
                    if (k == "environment") {
                        put(
                            "environment",
                            kotlinx.serialization.json.buildJsonObject { env.forEach { (ek, ev) -> put(ek, ev) } },
                        )
                    } else {
                        put(k, v)
                    }
                }
                if ("environment" !in service) {
                    put(
                        "environment",
                        kotlinx.serialization.json.buildJsonObject { env.forEach { (ek, ev) -> put(ek, ev) } },
                    )
                }
            }
        }

    private fun modifyService(
        compose: JsonObject,
        serviceName: String,
        transform: (JsonObject) -> JsonObject,
    ): JsonObject {
        val services = compose["services"] as? JsonObject ?: return compose
        val service = services[serviceName] as? JsonObject ?: return compose
        val newService = transform(service)
        val newServices =
            kotlinx.serialization.json.buildJsonObject {
                for ((k, v) in services) {
                    if (k == serviceName) put(k, newService) else put(k, v)
                }
            }
        return kotlinx.serialization.json.buildJsonObject {
            for ((k, v) in compose) {
                if (k == "services") put(k, newServices) else put(k, v)
            }
        }
    }
}
