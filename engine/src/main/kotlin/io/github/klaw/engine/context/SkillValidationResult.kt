package io.github.klaw.engine.context

data class SkillDetail(
    val name: String,
    val description: String,
    val source: String,
)

data class SkillValidationEntry(
    val name: String?,
    val directory: String,
    val source: String,
    val valid: Boolean,
    val error: String? = null,
)

data class SkillValidationReport(
    val skills: List<SkillValidationEntry>,
) {
    val total: Int get() = skills.size
    val valid: Int get() = skills.count { it.valid }
    val errors: Int get() = skills.count { !it.valid }
}
