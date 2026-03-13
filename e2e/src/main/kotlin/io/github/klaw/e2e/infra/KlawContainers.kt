package io.github.klaw.e2e.infra

import io.github.oshai.kotlinlogging.KotlinLogging
import org.slf4j.LoggerFactory
import org.testcontainers.Testcontainers
import org.testcontainers.containers.BindMode
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.Network
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.io.File
import java.time.Duration

private val logger = KotlinLogging.logger {}

class KlawContainers(
    private val wireMockPort: Int,
    private val engineJson: String,
    private val gatewayJson: String,
    private val workspaceDir: File,
) {
    private val network = Network.newNetwork()
    private lateinit var engineContainer: GenericContainer<*>
    private lateinit var gatewayContainer: GenericContainer<*>

    private lateinit var configDir: File
    private lateinit var engineDataDir: File
    private lateinit var engineStateDir: File
    private lateinit var gatewayDataDir: File
    private lateinit var gatewayStateDir: File

    val gatewayHost: String get() = gatewayContainer.host
    val gatewayMappedPort: Int get() = gatewayContainer.getMappedPort(GATEWAY_CONSOLE_PORT)
    val engineDataPath: File get() = engineDataDir

    fun start() {
        Testcontainers.exposeHostPorts(wireMockPort)

        setupDirectories()

        buildImages()

        engineContainer =
            GenericContainer(DockerImageName.parse(ENGINE_IMAGE))
                .withNetwork(network)
                .withNetworkAliases("engine")
                .withEnv("HOME", "/home/klaw")
                .withEnv("KLAW_ENGINE_BIND", "0.0.0.0")
                .withEnv("KLAW_ENGINE_PORT", ENGINE_PORT.toString())
                .withEnv("KLAW_WORKSPACE", "/workspace")
                .withFileSystemBind(configDir.absolutePath, "/home/klaw/.config/klaw", BindMode.READ_WRITE)
                .withFileSystemBind(engineDataDir.absolutePath, "/home/klaw/.local/share/klaw", BindMode.READ_WRITE)
                .withFileSystemBind(engineStateDir.absolutePath, "/home/klaw/.local/state/klaw", BindMode.READ_WRITE)
                .withFileSystemBind(workspaceDir.absolutePath, "/workspace", BindMode.READ_WRITE)
                .waitingFor(
                    Wait
                        .forLogMessage(".*EngineSocketServer started on.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(STARTUP_TIMEOUT_SECONDS)),
                ).withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("klaw-e2e")).withPrefix("engine"))

        logger.info { "Starting engine container..." }
        engineContainer.start()
        logger.info { "Engine container started" }

        // Gateway needs its own config directory
        val gatewayConfigDir =
            File(configDir.parentFile, "gateway-config").apply {
                mkdirs()
                setWritable(true, false)
                setReadable(true, false)
                setExecutable(true, false)
            }
        File(gatewayConfigDir, "gateway.json").writeText(gatewayJson)

        gatewayContainer =
            GenericContainer(DockerImageName.parse(GATEWAY_IMAGE))
                .withNetwork(network)
                .withEnv("HOME", "/home/klaw")
                .withEnv("KLAW_ENGINE_HOST", "engine")
                .withEnv("KLAW_ENGINE_PORT", ENGINE_PORT.toString())
                .withFileSystemBind(gatewayConfigDir.absolutePath, "/home/klaw/.config/klaw", BindMode.READ_WRITE)
                .withFileSystemBind(gatewayDataDir.absolutePath, "/home/klaw/.local/share/klaw", BindMode.READ_WRITE)
                .withFileSystemBind(gatewayStateDir.absolutePath, "/home/klaw/.local/state/klaw", BindMode.READ_WRITE)
                .withExposedPorts(GATEWAY_CONSOLE_PORT)
                .waitingFor(
                    Wait
                        .forLogMessage(".*Ktor server started on port.*", 1)
                        .withStartupTimeout(Duration.ofSeconds(GATEWAY_STARTUP_TIMEOUT_SECONDS)),
                ).withLogConsumer(Slf4jLogConsumer(LoggerFactory.getLogger("klaw-e2e")).withPrefix("gateway"))

        logger.info { "Starting gateway container..." }
        gatewayContainer.start()
        logger.info { "Gateway container started on port $gatewayMappedPort" }
    }

    fun stop() {
        if (::gatewayContainer.isInitialized) gatewayContainer.stop()
        if (::engineContainer.isInitialized) engineContainer.stop()
        network.close()
    }

    private fun buildImages() {
        val projectRoot = findProjectRoot()
        buildImage(projectRoot, "docker/engine/Dockerfile", ENGINE_IMAGE)
        buildImage(projectRoot, "docker/gateway/Dockerfile", GATEWAY_IMAGE)
    }

    private fun buildImage(
        projectRoot: File,
        dockerfile: String,
        tag: String,
    ) {
        logger.info { "Building Docker image $tag from $dockerfile..." }
        val process =
            ProcessBuilder("docker", "build", "-t", tag, "-f", dockerfile, ".")
                .directory(projectRoot)
                .redirectErrorStream(true)
                .start()

        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            logger.error { "Docker build failed for $tag:\n$output" }
            error("Docker build failed for $tag (exit code $exitCode)")
        }
        logger.info { "Docker image $tag built successfully" }
    }

    private fun setupDirectories() {
        val tmpBase = File(System.getProperty("java.io.tmpdir"), "klaw-e2e-${System.currentTimeMillis()}")
        tmpBase.mkdirs()

        configDir = File(tmpBase, "engine-config").apply { mkdirs() }
        engineDataDir = File(tmpBase, "engine-data").apply { mkdirs() }
        engineStateDir = File(tmpBase, "engine-state").apply { mkdirs() }
        gatewayDataDir = File(tmpBase, "gateway-data").apply { mkdirs() }
        gatewayStateDir = File(tmpBase, "gateway-state").apply { mkdirs() }

        // Containers run as UID 10001 — dirs need world-writable permissions
        listOf(configDir, engineDataDir, engineStateDir, gatewayDataDir, gatewayStateDir, workspaceDir)
            .forEach { dir ->
                dir.setWritable(true, false)
                dir.setReadable(true, false)
                dir.setExecutable(true, false)
            }

        File(configDir, "engine.json").writeText(engineJson)
        // Engine config dir gets engine.json; gateway config is separate
    }

    private fun findProjectRoot(): File {
        var dir = File(System.getProperty("user.dir"))
        while (dir.parentFile != null) {
            if (File(dir, "settings.gradle.kts").exists() && File(dir, "docker").isDirectory) {
                return dir
            }
            dir = dir.parentFile
        }
        error("Could not find project root (no settings.gradle.kts + docker/ found)")
    }

    companion object {
        private const val ENGINE_IMAGE = "klaw-engine-e2e:latest"
        private const val GATEWAY_IMAGE = "klaw-gateway-e2e:latest"
        private const val ENGINE_PORT = 7470
        private const val GATEWAY_CONSOLE_PORT = 37474
        private const val STARTUP_TIMEOUT_SECONDS = 180L
        private const val GATEWAY_STARTUP_TIMEOUT_SECONDS = 60L
    }
}
