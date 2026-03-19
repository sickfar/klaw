package io.github.klaw.engine.tools

import io.github.klaw.common.config.EngineConfig
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class WebFetchToolFactory {
    @Singleton
    fun webFetchTool(config: EngineConfig): WebFetchTool = WebFetchTool(config.webFetch)
}
