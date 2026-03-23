package io.github.klaw.cli.init

import io.github.klaw.cli.ui.AnsiColors
import io.github.klaw.cli.util.CliLogger

private const val MODELS_FETCH_TIMEOUT = 10
private const val SEARCH_VALIDATION_TIMEOUT = 10

/**
 * Validates LLM and web search API keys by making test requests via curl.
 */
internal class ApiKeyValidator(
    private val commandOutput: (String) -> String?,
    private val printer: (String) -> Unit,
) {
    /**
     * Validates the LLM API key via a curl request.
     * For OpenAI-compatible: GET {providerUrl}/models with Bearer token.
     * For Anthropic: POST {providerUrl}/v1/messages with x-api-key header and minimal request.
     * Returns the raw JSON response if the key appears valid, else null.
     * Skips validation if the URL or key contain single-quote characters (injection prevention).
     */
    fun validateApiKey(
        providerUrl: String,
        llmApiKey: String,
        providerType: String = "openai-compatible",
    ): String? {
        if ("'" in providerUrl || "'" in llmApiKey) {
            CliLogger.warn { "unsafe characters in URL or key, skipping validation" }
            printer(
                "${AnsiColors.YELLOW}⚠ URL or key contains unsafe characters, skipping validation.${AnsiColors.RESET}",
            )
            // Return non-null so caller proceeds (consistent with "skipping" message)
            return ""
        }
        return if (providerType == "anthropic") {
            validateAnthropicApiKey(providerUrl, llmApiKey)
        } else {
            validateOpenAiApiKey(providerUrl, llmApiKey)
        }
    }

    private fun validateOpenAiApiKey(
        providerUrl: String,
        llmApiKey: String,
    ): String? {
        val url = "${providerUrl.trimEnd('/')}/models"
        val cmd = "curl -s -m $MODELS_FETCH_TIMEOUT -H 'Authorization: Bearer $llmApiKey' '$url'"
        val response = commandOutput(cmd) ?: return null
        return if (response.contains("\"data\"")) {
            CliLogger.debug { "API key validation passed" }
            printer("${AnsiColors.GREEN}✓ API key valid${AnsiColors.RESET}")
            response
        } else {
            CliLogger.warn { "API key validation failed" }
            printer("${AnsiColors.YELLOW}⚠ API key validation failed.${AnsiColors.RESET}")
            null
        }
    }

    private fun validateAnthropicApiKey(
        providerUrl: String,
        llmApiKey: String,
    ): String? {
        val url = "${providerUrl.trimEnd('/')}/v1/messages"
        val body = buildAnthropicValidationBody()
        val cmd =
            "curl -s -m $MODELS_FETCH_TIMEOUT " +
                "-H 'x-api-key: $llmApiKey' " +
                "-H 'anthropic-version: 2023-06-01' " +
                "-H 'Content-Type: application/json' " +
                "-d '$body' " +
                "'$url'"
        val response = commandOutput(cmd) ?: return null
        val isAuthError =
            response.contains("\"authentication_error\"") ||
                response.contains("\"permission_error\"")
        return when {
            response.contains("\"content\"") && !response.contains("\"error\"") -> {
                CliLogger.debug { "Anthropic API key validation passed" }
                printer("${AnsiColors.GREEN}✓ API key valid${AnsiColors.RESET}")
                response
            }

            isAuthError -> {
                CliLogger.warn { "Anthropic API key validation failed: auth error" }
                printer("${AnsiColors.YELLOW}⚠ API key validation failed.${AnsiColors.RESET}")
                null
            }

            else -> {
                // Transient error (rate limit, overload) — accept key
                CliLogger.warn { "Anthropic API validation inconclusive, proceeding" }
                printer(
                    "${AnsiColors.YELLOW}⚠ Validation inconclusive (service may be busy), " +
                        "proceeding.${AnsiColors.RESET}",
                )
                response
            }
        }
    }

    private fun buildAnthropicValidationBody(): String {
        val model = "claude-sonnet-4-5-20250514"
        return """{"model":"$model","max_tokens":1,"messages":[{"role":"user","content":"hi"}]}"""
    }

    /**
     * Validates a web search API key by making a test request.
     * - Brave: checks HTTP status code via `-w "%{http_code}"`
     * - Tavily: checks for "results" in response body
     * Returns true if validation passed, false otherwise.
     */
    fun validateSearchApiKey(
        providerName: String,
        apiKey: String,
    ): Boolean {
        // Block single-quote (shell injection), double-quote and backslash (JSON injection in Tavily -d body)
        if ("'" in apiKey || "\"" in apiKey || "\\" in apiKey) {
            CliLogger.warn { "unsafe characters in search API key, validation failed" }
            printer(
                "${AnsiColors.YELLOW}⚠ API key contains unsafe characters.${AnsiColors.RESET}",
            )
            return false
        }
        val cmd =
            when (providerName) {
                "brave" -> {
                    "curl -s -o /dev/null -w \"%{http_code}\" -m $SEARCH_VALIDATION_TIMEOUT " +
                        "-H 'X-Subscription-Token: $apiKey' " +
                        "'https://api.search.brave.com/res/v1/web/search?q=test'"
                }

                "tavily" -> {
                    "curl -s -m $SEARCH_VALIDATION_TIMEOUT -X POST " +
                        "'https://api.tavily.com/search' " +
                        "-H 'Content-Type: application/json' " +
                        "-d '{\"api_key\":\"$apiKey\",\"query\":\"test\",\"max_results\":1}'"
                }

                else -> {
                    return false
                }
            }
        val response = commandOutput(cmd) ?: return false
        val valid =
            when (providerName) {
                "brave" -> response.trim() == "200"
                "tavily" -> response.contains("\"results\"")
                else -> false
            }
        return if (valid) {
            CliLogger.debug { "search API key validation passed" }
            printer("${AnsiColors.GREEN}✓ Search API key valid${AnsiColors.RESET}")
            true
        } else {
            CliLogger.warn { "search API key validation failed" }
            printer("${AnsiColors.YELLOW}⚠ Search API key validation failed.${AnsiColors.RESET}")
            false
        }
    }
}
