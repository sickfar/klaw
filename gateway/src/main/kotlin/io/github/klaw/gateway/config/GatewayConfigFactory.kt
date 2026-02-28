package io.github.klaw.gateway.config

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.parseGatewayConfig
import io.github.klaw.common.paths.KlawPaths
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.io.File

@Factory
class GatewayConfigFactory {
    @Singleton
    fun gatewayConfig(): GatewayConfig {
        val configFile = File("${KlawPaths.config}/gateway.json")
        val jsonContent =
            if (configFile.exists()) {
                configFile.readText()
            } else {
                GatewayConfigFactory::class.java
                    .getResourceAsStream("/gateway.json")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("gateway.json not found at ${configFile.absolutePath} or on classpath")
            }
        return parseGatewayConfig(jsonContent)
    }
}
