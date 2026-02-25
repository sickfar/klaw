package io.github.klaw.engine.context

interface SkillRegistry {
    suspend fun listSkillDescriptions(): List<String>
}
