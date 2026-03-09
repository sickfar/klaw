package io.github.klaw.engine.mcp

import io.github.klaw.common.config.EnvVarResolver
import io.github.klaw.common.config.McpConfig
import io.github.klaw.common.config.McpServerConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.event.ApplicationEventListener
import io.micronaut.context.event.ShutdownEvent
import io.micronaut.context.event.StartupEvent
import jakarta.inject.Singleton
import kotlinx.coroutines.runBlocking

private val logger = KotlinLogging.logger {}

@Singleton
class McpStartupListener(
    private val mcpConfig: McpConfig,
    private val mcpToolRegistry: McpToolRegistry,
) : ApplicationEventListener<StartupEvent> {
    override fun onApplicationEvent(event: StartupEvent) {
        val enabledServers = mcpConfig.servers.filter { it.value.enabled }
        if (enabledServers.isEmpty()) {
            logger.debug { "mcp: no enabled servers configured" }
            return
        }
        logger.info { "mcp: starting ${enabledServers.size} server(s)" }
        for ((name, config) in enabledServers) {
            connectServer(name, config)
        }
    }

    private fun connectServer(
        name: String,
        config: McpServerConfig,
    ) {
        try {
            val transport = createTransport(name, config)
            if (transport == null) {
                logger.warn { "mcp: skipping server=$name, cannot create transport" }
                return
            }
            val client = McpClient(transport, name, config.timeoutMs)
            runBlocking {
                startTransportIfNeeded(transport)
                client.initialize()
                val tools = client.listTools()
                mcpToolRegistry.registerClient(name, client)
                mcpToolRegistry.registerTools(name, tools)
                logger.debug {
                    "mcp: server=$name connected, tools=${tools.size} names=${tools.map { it.name }}"
                }
            }
        } catch (e: McpClientException) {
            logger.warn(e) { "mcp: failed to connect server=$name" }
        } catch (e: java.io.IOException) {
            logger.warn(e) { "mcp: IO error connecting server=$name" }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            logger.warn(e) { "mcp: timeout connecting server=$name" }
        }
    }

    private fun createTransport(
        name: String,
        config: McpServerConfig,
    ): McpTransport? =
        when (config.transport) {
            "stdio" -> {
                createStdioTransport(name, config)
            }

            "http" -> {
                createHttpTransport(config)
            }

            else -> {
                logger.warn { "mcp: unknown transport '${config.transport}' for server=$name" }
                null
            }
        }

    private fun createStdioTransport(
        name: String,
        config: McpServerConfig,
    ): McpTransport? {
        val command = config.command
        if (command == null) {
            logger.warn { "mcp: stdio server=$name missing 'command'" }
            return null
        }
        val isDocker = isRunningInDocker()
        return if (isDocker) {
            createDockerStdioTransport(name, command, config)
        } else {
            StdioTransport(command, config.args, config.env, KlawPaths.workspace)
        }
    }

    private fun createDockerStdioTransport(
        name: String,
        command: String,
        config: McpServerConfig,
    ): McpTransport? {
        if (StackDetector.isDockerCommand(command)) {
            return StdioTransport(command, config.args, config.env, null)
        }
        val image = StackDetector.resolve(command)
        if (image == null) {
            logger.warn {
                "mcp: server '$name': command '$command' has no known Docker image — " +
                    "use HTTP transport or wrap in a docker command"
            }
            return null
        }
        return DockerStdioTransport(
            serverName = name,
            image = image,
            command = command,
            args = config.args,
            env = config.env,
        )
    }

    private fun createHttpTransport(config: McpServerConfig): McpTransport? {
        val url = config.url
        if (url == null) {
            logger.warn { "mcp: http server missing 'url'" }
            return null
        }
        val resolvedApiKey = EnvVarResolver.resolve(config.apiKey)
        return HttpTransport(url, resolvedApiKey)
    }

    private suspend fun startTransportIfNeeded(transport: McpTransport) {
        if (transport is StdioTransport) {
            transport.start()
        } else if (transport is DockerStdioTransport) {
            transport.start()
        }
    }

    private fun isRunningInDocker(): Boolean = java.io.File("/.dockerenv").exists()
}

@Singleton
class McpShutdownListener(
    private val mcpToolRegistry: McpToolRegistry,
) : ApplicationEventListener<ShutdownEvent> {
    override fun onApplicationEvent(event: ShutdownEvent) {
        logger.info { "mcp: shutting down" }
        runBlocking { mcpToolRegistry.closeAll() }
    }
}
