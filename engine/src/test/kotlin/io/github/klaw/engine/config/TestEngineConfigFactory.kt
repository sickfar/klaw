package io.github.klaw.engine.config

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.config.parseEngineConfig
import io.micronaut.context.annotation.Factory
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton

@Factory
@Replaces(factory = EngineConfigFactory::class)
class TestEngineConfigFactory {
    @Singleton
    @Replaces(bean = EngineConfig::class, factory = EngineConfigFactory::class)
    fun engineConfig(): EngineConfig {
        val jsonContent =
            TestEngineConfigFactory::class.java
                .getResourceAsStream("/engine.json")
                ?.bufferedReader()
                ?.readText()
                ?: error("engine.json not found on test classpath")
        return parseEngineConfig(jsonContent)
    }
}
