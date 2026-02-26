package io.github.klaw.engine.tools

import io.github.klaw.engine.context.SkillRegistry
import jakarta.inject.Singleton

@Singleton
class SkillTools(
    private val skillRegistry: SkillRegistry,
) {
    suspend fun list(): String {
        val skills = skillRegistry.listAll()
        if (skills.isEmpty()) return "No skills available"
        return skills.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    suspend fun load(name: String): String = skillRegistry.getFullContent(name) ?: "Error: skill '$name' not found"
}
