package io.github.klaw.common.config.schema

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Generates a JSON Schema (draft-07) from a [SerialDescriptor].
 *
 * @param descriptor the root descriptor to generate schema from
 * @param overrides map of dot-paths (e.g. ".memory.chunking.size") to extra constraint properties
 *                  that are merged into the generated schema node
 */
@OptIn(ExperimentalSerializationApi::class)
fun generateJsonSchema(
    descriptor: SerialDescriptor,
    overrides: Map<String, JsonObject> = emptyMap(),
    descriptions: Map<String, String> = emptyMap(),
): JsonObject =
    buildJsonObject {
        put("\$schema", "http://json-schema.org/draft-07/schema#")
        val inner = generateNode(descriptor, "", overrides, descriptions)
        for ((key, value) in inner) {
            put(key, value)
        }
    }

@OptIn(ExperimentalSerializationApi::class)
private fun generateNode(
    descriptor: SerialDescriptor,
    path: String,
    overrides: Map<String, JsonObject>,
    descriptions: Map<String, String>,
): JsonObject {
    val override = overrides[path]

    val base =
        when (descriptor.kind) {
            StructureKind.CLASS -> {
                generateClassNode(descriptor, path, overrides, descriptions)
            }

            StructureKind.LIST -> {
                generateListNode(descriptor, path, overrides, descriptions)
            }

            StructureKind.MAP -> {
                generateMapNode(descriptor, path, overrides, descriptions)
            }

            PrimitiveKind.STRING -> {
                buildJsonObject { put("type", "string") }
            }

            PrimitiveKind.INT, PrimitiveKind.LONG, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> {
                buildJsonObject { put("type", "integer") }
            }

            PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> {
                buildJsonObject { put("type", "number") }
            }

            PrimitiveKind.BOOLEAN -> {
                buildJsonObject { put("type", "boolean") }
            }

            else -> {
                buildJsonObject { put("type", "string") }
            }
        }

    return if (override != null) {
        mergeObjects(base, override)
    } else {
        base
    }
}

@OptIn(ExperimentalSerializationApi::class)
private fun generateClassNode(
    descriptor: SerialDescriptor,
    path: String,
    overrides: Map<String, JsonObject>,
    descriptions: Map<String, String>,
): JsonObject =
    buildJsonObject {
        put("type", "object")

        val requiredFields = mutableListOf<String>()
        val properties =
            buildJsonObject {
                for (i in 0 until descriptor.elementsCount) {
                    val name = descriptor.getElementName(i)
                    val elementDescriptor = descriptor.getElementDescriptor(i)
                    val elementPath = "$path.$name"

                    val node = generateNode(elementDescriptor, elementPath, overrides, descriptions)
                    val desc = descriptions[elementPath]
                    if (desc != null) {
                        put(name, mergeObjects(node, buildJsonObject { put("description", desc) }))
                    } else {
                        put(name, node)
                    }

                    if (!descriptor.isElementOptional(i)) {
                        requiredFields.add(name)
                    }
                }
            }

        if (properties.isNotEmpty()) {
            put("properties", properties)
        }

        if (requiredFields.isNotEmpty()) {
            put(
                "required",
                kotlinx.serialization.json.buildJsonArray {
                    requiredFields.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
                },
            )
        }

        put("additionalProperties", false)
    }

@OptIn(ExperimentalSerializationApi::class)
private fun generateListNode(
    descriptor: SerialDescriptor,
    path: String,
    overrides: Map<String, JsonObject>,
    descriptions: Map<String, String>,
): JsonObject =
    buildJsonObject {
        put("type", "array")
        val elementDescriptor = descriptor.getElementDescriptor(0)
        put("items", generateNode(elementDescriptor, "$path[]", overrides, descriptions))
    }

@OptIn(ExperimentalSerializationApi::class)
private fun generateMapNode(
    descriptor: SerialDescriptor,
    path: String,
    overrides: Map<String, JsonObject>,
    descriptions: Map<String, String>,
): JsonObject =
    buildJsonObject {
        put("type", "object")
        val valueDescriptor = descriptor.getElementDescriptor(1)
        put("additionalProperties", generateNode(valueDescriptor, "$path.*", overrides, descriptions) as JsonElement)
    }

private fun mergeObjects(
    base: JsonObject,
    extra: JsonObject,
): JsonObject =
    buildJsonObject {
        for ((key, value) in base) {
            put(key, value)
        }
        for ((key, value) in extra) {
            put(key, value)
        }
    }
