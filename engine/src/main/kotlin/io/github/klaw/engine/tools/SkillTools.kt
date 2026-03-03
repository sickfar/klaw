package io.github.klaw.engine.tools

import io.github.klaw.engine.context.SkillRegistry
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.inject.Singleton

private val logger = KotlinLogging.logger {}

@Singleton
class SkillTools(
    private val skillRegistry: SkillRegistry,
) {
    suspend fun list(): String {
        logger.trace { "skill_list" }
        val skills = skillRegistry.listAll()
        if (skills.isEmpty()) return "No skills available"
        return skills.joinToString("\n") { "- ${it.name}: ${it.description}" }
    }

    suspend fun load(name: String): String {
        logger.trace { "skill_load: name=$name" }
        val content = skillRegistry.getFullContent(name)
        if (content == null) {
            logger.debug { "skill_load: not found: $name" }
            return "Error: skill '$name' not found"
        }
        return content
    }
}
