package io.github.klaw.engine.tools

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton
import kotlinx.serialization.json.Json

private val logger = KotlinLogging.logger {}

@Singleton
class EngineHealthTools(
    private val healthProvider: EngineHealthProvider,
) {
    private val json = Json { encodeDefaults = true }

    suspend fun health(): String {
        logger.trace { "engine_health" }
        val health = healthProvider.getHealth()
        return json.encodeToString(EngineHealth.serializer(), health)
    }
}
