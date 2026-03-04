package io.github.klaw.common.config

enum class ConfigValueType {
    STRING,
    INT,
    LONG,
    DOUBLE,
    BOOLEAN,
    LIST_STRING,
    MAP_SECTION,
}

data class ConfigPropertyDescriptor(
    val path: String,
    val type: ConfigValueType,
    val description: String,
    val defaultValue: String?,
    val possibleValues: List<String>?,
    val sensitive: Boolean,
    val required: Boolean,
)
