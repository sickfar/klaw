package io.github.klaw.engine.tools

import io.github.klaw.common.config.WebSearchConfig
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlin.coroutines.cancellation.CancellationException

private val logger = KotlinLogging.logger {}

private const val MAX_RESULTS_LIMIT = 20

class WebSearchTool(
    private val config: WebSearchConfig,
    private val provider: WebSearchProvider,
) {
    suspend fun search(
        query: String,
        maxResults: Int?,
    ): String {
        val effectiveMax = (maxResults ?: config.maxResults).coerceIn(1, MAX_RESULTS_LIMIT)
        logger.trace { "web_search: provider=${config.provider} maxResults=$effectiveMax" }

        val results =
            try {
                provider.search(query, effectiveMax)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IllegalStateException) {
                logger.warn(e) { "web_search failed: provider=${config.provider}" }
                return "Error: ${e::class.simpleName}"
            } catch (e: java.io.IOException) {
                logger.warn(e) { "web_search failed: provider=${config.provider}" }
                return "Error: ${e::class.simpleName}"
            } catch (e: kotlinx.serialization.SerializationException) {
                logger.warn(e) { "web_search failed: provider=${config.provider}" }
                return "Error: ${e::class.simpleName}"
            } catch (e: IllegalArgumentException) {
                logger.warn(e) { "web_search failed: provider=${config.provider}" }
                return "Error: ${e::class.simpleName}"
            }

        logger.debug { "web_search completed: provider=${config.provider} results=${results.size}" }

        if (results.isEmpty()) {
            return "No results found for the given query.\nProvider: ${config.provider}"
        }

        return formatResults(query, results)
    }

    private fun formatResults(
        query: String,
        results: List<SearchResult>,
    ): String =
        buildString {
            append("Search results for: \"$query\"\n")
            append("Provider: ${config.provider}\n")
            append("Results: ${results.size}\n")
            results.forEachIndexed { index, result ->
                append("\n${index + 1}. [${result.title}](${result.url})")
                if (result.snippet.isNotBlank()) {
                    append("\n   ${result.snippet}")
                }
            }
        }
}
