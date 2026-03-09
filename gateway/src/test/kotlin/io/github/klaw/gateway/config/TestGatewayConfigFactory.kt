package io.github.klaw.gateway.config

import io.github.klaw.common.config.GatewayConfig
import io.github.klaw.common.config.parseGatewayConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.klaw.common.paths.KlawPathsSnapshot
import io.github.klaw.gateway.pairing.ConfigFileWatcher
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton

@Factory
@Replaces(factory = GatewayConfigFactory::class)
class TestGatewayConfigFactory {
    @Singleton
    @Replaces(bean = GatewayConfig::class, factory = GatewayConfigFactory::class)
    fun gatewayConfig(): GatewayConfig {
        val jsonContent =
            TestGatewayConfigFactory::class.java
                .getResourceAsStream("/gateway.json")
                ?.bufferedReader()
                ?.readText()
                ?: error("gateway.json not found on test classpath")
        return parseGatewayConfig(jsonContent)
    }

    @Singleton
    fun klawPathsSnapshot(): KlawPathsSnapshot = KlawPaths.snapshot()

    @Singleton
    fun configFileWatcher(): ConfigFileWatcher = ConfigFileWatcher(KlawPaths.config)
}
