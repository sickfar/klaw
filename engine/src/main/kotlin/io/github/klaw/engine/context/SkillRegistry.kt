package io.github.klaw.engine.context

data class SkillMeta(
    val name: String,
    val description: String,
)

interface SkillRegistry {
    suspend fun listSkillDescriptions(): List<String>

    suspend fun listAll(): List<SkillMeta>

    suspend fun getFullContent(name: String): String?
}
