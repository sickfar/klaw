package io.github.klaw.engine.llm

import io.github.klaw.common.config.ModelRef
import io.github.klaw.common.config.ResolvedProviderConfig
import io.github.klaw.common.llm.LlmRequest
import io.github.klaw.common.llm.LlmResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

interface LlmClient {
    suspend fun chat(
        request: LlmRequest,
        provider: ResolvedProviderConfig,
        model: ModelRef,
    ): LlmResponse

    fun chatStream(
        request: LlmRequest,
        provider: ResolvedProviderConfig,
        model: ModelRef,
    ): Flow<StreamEvent> =
        flow {
            emit(StreamEvent.End(chat(request, provider, model)))
        }
}
