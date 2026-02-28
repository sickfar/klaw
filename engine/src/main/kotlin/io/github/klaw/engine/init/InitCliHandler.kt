package io.github.klaw.engine.init

import io.github.klaw.common.config.EngineConfig
import io.github.klaw.common.llm.LlmMessage
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.engine.llm.LlmRouter
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

private const val IDENTITY_SYSTEM_PROMPT = """You are a configuration assistant generating workspace files for an AI agent.
Generate two markdown files as a JSON object with keys: identity, user.
identity: name, role, and personality of the agent.
user: information about the user from their description.

Respond ONLY with valid JSON: {"identity": "...", "user": "..."}"""

@Singleton
class InitCliHandler(
    private val llmRouter: LlmRouter,
    private val config: EngineConfig,
) {
    @Suppress("FunctionOnlyReturningConstant")
    fun handleStatus(): String = """{"status":"ok","engine":"klaw"}"""

    suspend fun handleGenerateIdentity(params: Map<String, String>): String {
        val name = params["name"] ?: "Klaw"
        val role = params["role"] ?: ""
        val userInfo = params["user_info"] ?: ""

        val userPrompt = "Name: $name. Role: $role. User info: $userInfo."

        return try {
            val request =
                LlmRequest(
                    messages =
                        listOf(
                            LlmMessage("system", IDENTITY_SYSTEM_PROMPT),
                            LlmMessage("user", userPrompt),
                        ),
                )
            val response = llmRouter.chat(request, config.routing.default)
            val content = response.content ?: return """{"error":"empty LLM response"}"""
            logger.debug { "Identity generation complete size=${content.length}" }
            content
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.warn { "Identity generation failed: ${e::class.simpleName}" }
            """{"error":"${e::class.simpleName}"}"""
        }
    }
}
