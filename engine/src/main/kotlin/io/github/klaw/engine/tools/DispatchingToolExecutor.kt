package io.github.klaw.engine.tools

import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolResult
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

@Singleton
@Replaces(NoOpToolExecutor::class)
class DispatchingToolExecutor(
    private val registry: ToolRegistryImpl,
) : ToolExecutor {
    override suspend fun executeAll(toolCalls: List<ToolCall>): List<ToolResult> =
        coroutineScope {
            toolCalls
                .map { call ->
                    async { registry.execute(call) }
                }.awaitAll()
        }
}
