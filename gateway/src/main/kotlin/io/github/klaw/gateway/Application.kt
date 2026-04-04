package io.github.klaw.gateway

import io.github.klaw.common.config.EnvVarResolver
import io.github.klaw.common.config.parseGatewayConfig
import io.github.klaw.common.paths.KlawPaths
import io.micronaut.context.env.MapPropertySource
import io.micronaut.runtime.Micronaut
import org.slf4j.bridge.SLF4JBridgeHandler
import java.io.File

object Application {
    private const val DEFAULT_PORT = 37474
    private const val DEFAULT_BIND = "127.0.0.1"

    @JvmStatic
    @Suppress("SpreadOperator")
    fun main(args: Array<String>) {
        SLF4JBridgeHandler.removeHandlersForRootLogger()
        SLF4JBridgeHandler.install()

        val serverProps = resolveServerProperties()

        Micronaut
            .build(*args)
            .propertySources(MapPropertySource.of("gateway-server", serverProps))
            .mainClass(Application::class.java)
            .start()
    }

    private fun resolveServerProperties(): Map<String, Any> {
        val port = extractPort()
        val bind = System.getenv("KLAW_GATEWAY_BIND") ?: DEFAULT_BIND
        return mapOf(
            "micronaut.server.port" to port,
            "micronaut.server.host" to bind,
        )
    }

    private fun extractPort(): Int {
        val configFile = File("${KlawPaths.config}/gateway.json")
        if (!configFile.exists()) return DEFAULT_PORT
        return try {
            val raw = EnvVarResolver.resolveAll(configFile.readText())
            val config = parseGatewayConfig(raw)
            config.channels.websocket.values
                .firstOrNull()
                ?.port ?: DEFAULT_PORT
        } catch (_: Exception) {
            DEFAULT_PORT
        }
    }
}
