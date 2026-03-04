package io.github.klaw.cli.ui

import io.github.klaw.common.config.ConfigPropertyDescriptor
import io.github.klaw.common.config.ConfigValueType
import io.github.klaw.common.config.formatConfigValue
import io.github.klaw.common.config.getByPath
import kotlinx.serialization.json.JsonObject

internal fun expandWildcardDescriptors(
    descriptors: List<ConfigPropertyDescriptor>,
    config: JsonObject,
): List<ConfigPropertyDescriptor> {
    val result = mutableListOf<ConfigPropertyDescriptor>()
    for (d in descriptors) {
        val starIdx = d.path.indexOf('*')
        if (starIdx < 0) {
            result.add(d)
            continue
        }
        val parentPath = d.path.substring(0, starIdx).trimEnd('.')
        val childSuffix = d.path.substring(starIdx + 1).trimStart('.')
        val mapElement = if (parentPath.isEmpty()) config else config.getByPath(parentPath)
        if (mapElement !is JsonObject) continue
        for (key in mapElement.keys) {
            val concretePath = if (childSuffix.isEmpty()) "$parentPath.$key" else "$parentPath.$key.$childSuffix"
            result.add(d.copy(path = concretePath))
        }
    }
    return result
}

internal fun buildItems(
    descriptors: List<ConfigPropertyDescriptor>,
    config: JsonObject,
): List<EditorItem> {
    val mapSectionDescriptors = descriptors.filter { it.type == ConfigValueType.MAP_SECTION }
    val mapSectionPaths = mapSectionDescriptors.map { it.path.trimEnd('*').trimEnd('.') }.toSet()
    val nonMapDescriptors = descriptors.filter { it.type != ConfigValueType.MAP_SECTION }
    val (mapChildDescriptors, standaloneDescriptors) =
        nonMapDescriptors.partition { d ->
            d.path.contains('*') && mapSectionPaths.any { mapPath -> d.path.startsWith("$mapPath.*") }
        }
    val result = mutableListOf<EditorItem>()
    result.addAll(buildMapSectionItems(mapSectionDescriptors, mapChildDescriptors, config))
    result.addAll(buildStandaloneItems(standaloneDescriptors, config))
    return result
}

private fun buildMapSectionItems(
    mapSectionDescriptors: List<ConfigPropertyDescriptor>,
    mapChildDescriptors: List<ConfigPropertyDescriptor>,
    config: JsonObject,
): List<EditorItem> {
    val result = mutableListOf<EditorItem>()
    for (mapDesc in mapSectionDescriptors) {
        val mapPath = mapDesc.path.trimEnd('*').trimEnd('.')
        result.add(EditorItem.MapSectionHeader(mapPath = mapPath, wildcardPath = mapDesc.path))
        val childPrefix = "$mapPath.*."
        val children =
            mapChildDescriptors.filter {
                it.path.startsWith(childPrefix) && it.type != ConfigValueType.LIST_STRING
            }
        val mapElement = if (mapPath.isEmpty()) config else config.getByPath(mapPath)
        val keys = if (mapElement is JsonObject) mapElement.keys.toList() else emptyList()
        for (key in keys) {
            result.add(EditorItem.MapKeyHeader(mapPath = mapPath, key = key))
            for (childDesc in children) {
                val childSuffix = childDesc.path.removePrefix(childPrefix)
                val concretePath = "$mapPath.$key.$childSuffix"
                result.add(buildMapChildProperty(childDesc, concretePath, childSuffix, config))
            }
        }
    }
    return result
}

private fun buildMapChildProperty(
    childDesc: ConfigPropertyDescriptor,
    concretePath: String,
    childSuffix: String,
    config: JsonObject,
): EditorItem.Property {
    val element = config.getByPath(concretePath)
    val isExplicit = element != null
    val displayValue =
        if (isExplicit) {
            formatConfigValue(element, childDesc.type, childDesc.sensitive)
        } else {
            childDesc.defaultValue ?: ""
        }
    return EditorItem.Property(
        descriptor = childDesc.copy(path = concretePath),
        isExplicit = isExplicit,
        displayValue = displayValue,
        displayPath = "    $childSuffix",
    )
}

private fun buildStandaloneItems(
    standaloneDescriptors: List<ConfigPropertyDescriptor>,
    config: JsonObject,
): List<EditorItem> {
    val expandedStandalone = expandWildcardDescriptors(standaloneDescriptors, config)
    val editable =
        expandedStandalone.filter { d ->
            d.type != ConfigValueType.MAP_SECTION && d.type != ConfigValueType.LIST_STRING
        }
    val explicit = mutableListOf<EditorItem.Property>()
    val defaults = mutableListOf<EditorItem.Property>()
    for (d in editable) {
        val element = config.getByPath(d.path)
        val isExplicit = element != null
        val displayValue =
            if (isExplicit) {
                formatConfigValue(element, d.type, d.sensitive)
            } else {
                d.defaultValue ?: ""
            }
        val item = EditorItem.Property(descriptor = d, isExplicit = isExplicit, displayValue = displayValue)
        if (isExplicit) explicit.add(item) else defaults.add(item)
    }
    val result = mutableListOf<EditorItem>()
    result.addAll(explicit)
    if (explicit.isNotEmpty() && defaults.isNotEmpty()) {
        result.add(EditorItem.SectionDivider("--- Defaults (not set) ---"))
    }
    result.addAll(defaults)
    return result
}
