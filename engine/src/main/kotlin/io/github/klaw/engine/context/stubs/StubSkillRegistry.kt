package io.github.klaw.engine.context.stubs

import io.github.klaw.engine.context.SkillMeta
import io.github.klaw.engine.context.SkillRegistry
import jakarta.inject.Singleton

@Singleton
class StubSkillRegistry : SkillRegistry {
    override fun discover() {
        // No-op: stub has no skills to discover
    }

    override suspend fun listSkillDescriptions(): List<String> = emptyList()

    override suspend fun listAll(): List<SkillMeta> = emptyList()

    override suspend fun getFullContent(name: String): String? = null
}
