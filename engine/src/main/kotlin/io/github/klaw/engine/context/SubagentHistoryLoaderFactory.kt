package io.github.klaw.engine.context

import io.github.klaw.common.paths.KlawPaths
import io.micronaut.context.annotation.Factory
import jakarta.inject.Singleton

@Factory
class SubagentHistoryLoaderFactory {
    @Singleton
    fun subagentHistoryLoader(): SubagentHistoryLoader = SubagentHistoryLoader(KlawPaths.conversations)
}
