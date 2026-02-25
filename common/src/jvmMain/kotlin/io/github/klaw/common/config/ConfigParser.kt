package io.github.klaw.common.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration

private val yaml = Yaml(
    configuration = YamlConfiguration(
        strictMode = false,
    ),
)

fun parseGatewayConfig(yamlString: String): GatewayConfig = yaml.decodeFromString(GatewayConfig.serializer(), yamlString)

fun parseEngineConfig(yamlString: String): EngineConfig = yaml.decodeFromString(EngineConfig.serializer(), yamlString)
