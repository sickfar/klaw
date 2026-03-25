package io.github.klaw.engine.config

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.parseEngineConfig
import io.github.klaw.common.paths.KlawPaths
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton
import java.io.File

private val logger = KotlinLogging.logger {}

@Factory
class EngineConfigFactory {
    @Singleton
    fun engineConfig(): EngineConfig {
        val configFile = File("${KlawPaths.config}/engine.json")
        val jsonContent =
            if (configFile.exists()) {
                configFile.readText()
            } else {
                // Fallback to classpath resource for development
                EngineConfigFactory::class.java
                    .getResourceAsStream("/engine.json")
                    ?.bufferedReader()
                    ?.readText()
                    ?: error("engine.json not found at ${configFile.absolutePath} or on classpath")
            }
        val config = parseEngineConfig(jsonContent)
        logger.info {
            "Engine config loaded: ${if (configFile.exists()) configFile.absolutePath else "classpath:/engine.json"}"
        }
        return config
    }
}
