package io.github.klaw.engine.context

import io.github.klaw.common.llm.ToolDef

interface ToolRegistry {
    suspend fun listTools(): List<ToolDef>
}
