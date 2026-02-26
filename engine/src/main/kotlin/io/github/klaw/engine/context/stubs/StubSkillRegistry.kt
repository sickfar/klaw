package io.github.klaw.engine.context.stubs

import io.github.klaw.engine.context.SkillMeta
import io.github.klaw.engine.context.SkillRegistry
import jakarta.inject.Singleton

@Singleton
class StubSkillRegistry : SkillRegistry {
    override suspend fun listSkillDescriptions(): List<String> = emptyList()

    override suspend fun listAll(): List<SkillMeta> = emptyList()

    override suspend fun getFullContent(name: String): String? = null
}
