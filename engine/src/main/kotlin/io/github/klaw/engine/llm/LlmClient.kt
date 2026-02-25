package io.github.klaw.engine.llm

import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ProviderConfig
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import kotlinx.coroutines.flow.Flow

interface LlmClient {
    suspend fun chat(
        request: LlmRequest,
        provider: ProviderConfig,
        model: ModelRef,
    ): LlmResponse

    @Suppress("UnusedParameter")
    fun chatStream(
        request: LlmRequest,
        provider: ProviderConfig,
        model: ModelRef,
    ): Flow<String> = throw UnsupportedOperationException("chatStream is Post-MVP P2")
}
