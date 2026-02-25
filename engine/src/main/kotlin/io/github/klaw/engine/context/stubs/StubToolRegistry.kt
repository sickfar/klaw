package io.github.klaw.engine.context.stubs

import io.github.klaw.common.llm.ToolDef
import io.github.klaw.engine.context.ToolRegistry
import jakarta.inject.Singleton

@Singleton
class StubToolRegistry : ToolRegistry {
    override suspend fun listTools(): List<ToolDef> = emptyList()
}
