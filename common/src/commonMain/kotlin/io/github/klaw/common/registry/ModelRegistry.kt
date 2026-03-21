package io.github.klaw.common.registry

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ModelCapabilities(
    val contextLength: Int,
    val maxOutput: Int = 0,
    val image: Boolean = false,
    val video: Boolean = false,
    val audio: Boolean = false,
)

/**
 * Built-in registry of known models and their capabilities.
 *
 * Data is maintained in `common/src/commonMain/resources/model-registry.json`.
 * JVM reads from classpath; Native uses a generated constant.
 *
 * Entries use raw model IDs (without provider prefix).
 * Lookup strips the provider prefix automatically:
 * `ModelRegistry.get("zai/glm-5")` → matches `"glm-5"`.
 *
 */
object ModelRegistry {
    private val json = Json { ignoreUnknownKeys = true }

    private val models: Map<String, ModelCapabilities> by lazy {
        json.decodeFromString<Map<String, ModelCapabilities>>(loadRegistryJson())
    }

    /** Looks up capabilities by model ID (with or without provider prefix). */
    fun get(modelId: String): ModelCapabilities? = models[modelId] ?: models[modelId.substringAfter("/")]

    /** Returns context length for the model, or null if unknown. */
    fun contextLength(modelId: String): Int? = get(modelId)?.contextLength

    /** Returns max output tokens for the model, or null if unknown or zero. */
    fun maxOutput(modelId: String): Int? = get(modelId)?.maxOutput?.takeIf { it > 0 }

    /** Returns true if the model supports image input, false if unknown or unsupported. */
    fun supportsImage(modelId: String): Boolean = get(modelId)?.image == true
}

internal expect fun loadRegistryJson(): String
