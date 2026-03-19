package io.github.klaw.engine.tools

import io.github.klaw.common.config.EngineConfig
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class WebSearchToolFactory {
    @Singleton
    fun webSearchTool(config: EngineConfig): WebSearchTool {
        val searchConfig = config.webSearch
        val provider =
            when (searchConfig.provider) {
                "brave" -> BraveSearchProvider(searchConfig)
                "tavily" -> TavilySearchProvider(searchConfig)
                else -> error("Unknown web search provider: ${searchConfig.provider}")
            }
        return WebSearchTool(searchConfig, provider)
    }
}
