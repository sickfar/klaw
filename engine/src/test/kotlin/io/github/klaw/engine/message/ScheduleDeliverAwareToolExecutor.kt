package io.github.klaw.engine.message

import io.github.klaw.common.llm.ToolCall
import io.github.klaw.common.llm.ToolResult
import io.github.klaw.engine.tools.ToolExecutor
import io.github.klaw.engine.workspace.ScheduleDeliverContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.coroutineContext

/**
 * A real [ToolExecutor] for tests that simulates `schedule_deliver` by reading
 * [ScheduleDeliverContext] from the coroutine context. This ensures proper context
 * propagation without relying on mockk's coAnswers context handling.
 */
class ScheduleDeliverAwareToolExecutor : ToolExecutor {
    override suspend fun executeAll(toolCalls: List<ToolCall>): List<ToolResult> =
        toolCalls.map { call ->
            if (call.name == "schedule_deliver") {
                val args = Json.parseToJsonElement(call.arguments).jsonObject
                val msg = args["message"]?.jsonPrimitive?.content ?: ""
                coroutineContext[ScheduleDeliverContext]?.sink?.deliver(msg)
                ToolResult(callId = call.id, content = "Message queued for delivery")
            } else {
                ToolResult(callId = call.id, content = "ok")
            }
        }
}
