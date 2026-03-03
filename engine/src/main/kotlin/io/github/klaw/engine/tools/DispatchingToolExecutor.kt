package io.github.klaw.engine.tools

import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolResult
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micronaut.context.annotation.Replaces
import jakarta.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

private val logger = KotlinLogging.logger {}

@Singleton
@Replaces(NoOpToolExecutor::class)
class DispatchingToolExecutor(
    private val registry: ToolRegistryImpl,
) : ToolExecutor {
    override suspend fun executeAll(toolCalls: List<ToolCall>): List<ToolResult> =
        coroutineScope {
            logger.debug { "executeAll: ${toolCalls.size} calls" }
            toolCalls
                .map { call ->
                    async { registry.execute(call) }
                }.awaitAll()
        }
}
