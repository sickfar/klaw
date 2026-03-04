package io.github.klaw.common.config.schema

import io.github.klaw.common.config.ConfigPropertyDescriptor
import io.github.klaw.common.config.ConfigValueType

fun generateMarkdown(
    title: String,
    descriptors: List<ConfigPropertyDescriptor>,
): String =
    buildString {
        appendLine("# $title")
        appendLine()

        if (descriptors.isEmpty()) return@buildString

        val grouped = descriptors.groupBy { it.path.substringBefore(".") }

        for ((section, props) in grouped) {
            appendLine("## $section")
            appendLine()
            appendLine("| Property | Type | Default | Description |")
            appendLine("|----------|------|---------|-------------|")

            for (prop in props) {
                val typeStr = formatType(prop.type)
                val defaultStr = formatDefault(prop.defaultValue)
                val descStr = formatDescription(prop)
                appendLine("| `${prop.path}` | $typeStr | $defaultStr | $descStr |")
            }

            appendLine()
        }
    }

private fun formatType(type: ConfigValueType): String =
    when (type) {
        ConfigValueType.STRING -> "string"
        ConfigValueType.INT -> "int"
        ConfigValueType.LONG -> "long"
        ConfigValueType.DOUBLE -> "double"
        ConfigValueType.BOOLEAN -> "boolean"
        ConfigValueType.LIST_STRING -> "list"
        ConfigValueType.MAP_SECTION -> "map"
    }

private fun formatDefault(defaultValue: String?): String = if (defaultValue != null) "`$defaultValue`" else "—"

private fun formatDescription(prop: ConfigPropertyDescriptor): String {
    val base = prop.description
    val values = prop.possibleValues
    return if (!values.isNullOrEmpty()) {
        "$base. Values: ${values.joinToString(", ") { "`$it`" }}"
    } else {
        base
    }
}
