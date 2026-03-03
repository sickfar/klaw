package io.github.klaw.gateway.config

import io.github.klaw.common.config.EnvVarResolver
import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.parseGatewayConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.paths.KlawPathsSnapshot
import io.github.klaw.gateway.pairing.ConfigFileWatcher
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.io.File

@Factory
class GatewayConfigFactory {
    @Singleton
    fun gatewayConfig(): GatewayConfig {
        val configFile = File("${KlawPaths.config}/gateway.json")
        val rawContent =
            if (configFile.exists()) {
                configFile.readText()
            } else {
                GatewayConfigFactory::class.java
                    .getResourceAsStream("/gateway.json")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("gateway.json not found at ${configFile.absolutePath} or on classpath")
            }
        val jsonContent = EnvVarResolver.resolveAll(rawContent)
        return parseGatewayConfig(jsonContent)
    }

    @Singleton
    fun klawPathsSnapshot(): KlawPathsSnapshot = KlawPaths.snapshot()

    @Singleton
    fun configFileWatcher(): ConfigFileWatcher = ConfigFileWatcher(KlawPaths.config)
}
