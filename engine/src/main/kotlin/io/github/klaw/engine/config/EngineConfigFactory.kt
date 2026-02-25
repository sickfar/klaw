package io.github.klaw.engine.config

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.io.File

@Factory
class EngineConfigFactory {
    @Singleton
    fun engineConfig(): EngineConfig {
        val configFile = File("${KlawPaths.config}/engine.yaml")
        val yamlContent =
            if (configFile.exists()) {
                configFile.readText()
            } else {
                // Fallback to classpath resource for development
                EngineConfigFactory::class.java
                    .getResourceAsStream("/engine.yaml")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("engine.yaml not found at ${configFile.absolutePath} or on classpath")
            }
        return parseEngineConfig(yamlContent)
    }
}
