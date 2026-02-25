package io.github.klaw.engine.context.stubs

import io.github.klaw.engine.context.WorkspaceLoader
import jakarta.inject.Singleton

@Singleton
class StubWorkspaceLoader : WorkspaceLoader {
    override suspend fun loadSystemPrompt(): String = ""
}
