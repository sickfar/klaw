package io.github.klaw.engine.context

import io.github.klaw.common.llm.ToolDef

interface ToolRegistry {
    suspend fun listTools(
        includeSkillList: Boolean = true,
        includeSkillLoad: Boolean = true,
        includeHeartbeatDeliver: Boolean = false,
    ): List<ToolDef>
}
