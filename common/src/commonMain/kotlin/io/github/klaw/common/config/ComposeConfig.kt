package io.github.klaw.common.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ComposeConfig(
    val services: Map<String, ComposeServiceConfig>,
    val volumes: Map<String, ComposeVolumeConfig>? = null,
)

@Serializable
data class ComposeServiceConfig(
    val image: String,
    val restart: String? = null,
    @SerialName("env_file") val envFile: String? = null,
    val environment: Map<String, String>? = null,
    @SerialName("depends_on") val dependsOn: List<String>? = null,
    val volumes: List<String>? = null,
    val ports: List<String>? = null,
)

@Serializable
data class ComposeVolumeConfig(
    val name: String? = null,
)
