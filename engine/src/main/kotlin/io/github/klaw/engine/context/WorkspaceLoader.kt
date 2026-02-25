package io.github.klaw.engine.context

interface WorkspaceLoader {
    suspend fun loadSystemPrompt(): String
}
