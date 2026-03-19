package io.github.klaw.engine.context.stubs

import io.github.klaw.engine.context.SkillDetail
import io.github.klaw.engine.context.SkillMeta
import io.github.klaw.engine.context.SkillRegistry
import io.github.klaw.engine.context.SkillValidationReport
import jakarta.inject.Singleton

@Singleton
class StubSkillRegistry : SkillRegistry {
    override fun discover() {
        // No-op: stub has no skills to discover
    }

    override suspend fun listSkillDescriptions(): List<String> = emptyList()

    override suspend fun listAll(): List<SkillMeta> = emptyList()

    override suspend fun getFullContent(name: String): String? = null

    override suspend fun listDetailed(): List<SkillDetail> = emptyList()

    override suspend fun validate(): SkillValidationReport = SkillValidationReport(emptyList())
}
