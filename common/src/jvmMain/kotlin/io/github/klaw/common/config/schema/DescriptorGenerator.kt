package io.github.klaw.common.config.schema

import io.github.klaw.common.config.ConfigDoc
import io.github.klaw.common.config.ConfigPropertyDescriptor
import io.github.klaw.common.config.ConfigValueType
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.StructureKind

@OptIn(ExperimentalSerializationApi::class)
fun generateDescriptors(
    descriptor: SerialDescriptor,
    rootClass: Class<*>,
): List<ConfigPropertyDescriptor> {
    val result = mutableListOf<ConfigPropertyDescriptor>()
    walkDescriptor(descriptor, rootClass, "", result)
    return result
}

@OptIn(ExperimentalSerializationApi::class)
private fun walkDescriptor(
    descriptor: SerialDescriptor,
    ownerClass: Class<*>,
    pathPrefix: String,
    result: MutableList<ConfigPropertyDescriptor>,
) {
    if (descriptor.kind != StructureKind.CLASS) return

    val annotationCache = buildAnnotationCache(ownerClass, descriptor)

    for (i in 0 until descriptor.elementsCount) {
        val name = descriptor.getElementName(i)
        val elementDescriptor = descriptor.getElementDescriptor(i)
        val path = if (pathPrefix.isEmpty()) name else "$pathPrefix.$name"
        val isOptional = descriptor.isElementOptional(i)
        val annotation = annotationCache[name] ?: readConfigDoc(ownerClass, name)

        when (elementDescriptor.kind) {
            StructureKind.CLASS -> walkNestedClass(elementDescriptor, path, result)
            StructureKind.MAP -> walkMapElement(elementDescriptor, path, annotation, isOptional, result)
            StructureKind.LIST -> walkListElement(elementDescriptor, path, annotation, isOptional, result)
            else -> addPrimitiveDescriptor(elementDescriptor, path, annotation, isOptional, result)
        }
    }
}

@OptIn(ExperimentalSerializationApi::class)
internal fun walkNestedClass(
    elementDescriptor: SerialDescriptor,
    path: String,
    result: MutableList<ConfigPropertyDescriptor>,
) {
    val nestedClass = resolveClass(elementDescriptor.serialName) ?: return
    walkDescriptor(elementDescriptor, nestedClass, path, result)
}

@OptIn(ExperimentalSerializationApi::class)
internal fun walkMapElement(
    elementDescriptor: SerialDescriptor,
    path: String,
    annotation: ConfigDoc?,
    isOptional: Boolean,
    result: MutableList<ConfigPropertyDescriptor>,
) {
    result.add(
        ConfigPropertyDescriptor(
            path = path,
            type = ConfigValueType.MAP_SECTION,
            description = annotation?.description ?: "",
            defaultValue = null,
            possibleValues = null,
            sensitive = annotation?.sensitive ?: false,
            required = !isOptional,
        ),
    )
    val valueDescriptor = elementDescriptor.getElementDescriptor(1)
    if (valueDescriptor.kind != StructureKind.CLASS) return
    val valueClass = resolveClass(valueDescriptor.serialName) ?: return
    walkDescriptor(valueDescriptor, valueClass, "$path.*", result)
}

@OptIn(ExperimentalSerializationApi::class)
internal fun walkListElement(
    elementDescriptor: SerialDescriptor,
    path: String,
    annotation: ConfigDoc?,
    isOptional: Boolean,
    result: MutableList<ConfigPropertyDescriptor>,
) {
    val itemDescriptor = elementDescriptor.getElementDescriptor(0)
    if (itemDescriptor.kind == StructureKind.CLASS) {
        val itemClass = resolveClass(itemDescriptor.serialName) ?: return
        walkDescriptor(itemDescriptor, itemClass, "$path[]", result)
        return
    }
    result.add(
        ConfigPropertyDescriptor(
            path = path,
            type = ConfigValueType.LIST_STRING,
            description = annotation?.description ?: "",
            defaultValue = null,
            possibleValues = annotation?.possibleValues?.toList()?.ifEmpty { null },
            sensitive = annotation?.sensitive ?: false,
            required = !isOptional,
        ),
    )
}

@OptIn(ExperimentalSerializationApi::class)
internal fun addPrimitiveDescriptor(
    elementDescriptor: SerialDescriptor,
    path: String,
    annotation: ConfigDoc?,
    isOptional: Boolean,
    result: MutableList<ConfigPropertyDescriptor>,
) {
    val valueType = mapPrimitiveKind(elementDescriptor.kind)
    val fromAnnotation = annotation?.possibleValues?.toList()?.ifEmpty { null }
    val possibleValues =
        fromAnnotation
            ?: if (valueType == ConfigValueType.BOOLEAN) listOf("true", "false") else null
    result.add(
        ConfigPropertyDescriptor(
            path = path,
            type = valueType,
            description = annotation?.description ?: "",
            defaultValue = null,
            possibleValues = possibleValues,
            sensitive = annotation?.sensitive ?: false,
            required = !isOptional,
        ),
    )
}

@OptIn(ExperimentalSerializationApi::class)
private fun mapPrimitiveKind(kind: kotlinx.serialization.descriptors.SerialKind): ConfigValueType =
    when (kind) {
        PrimitiveKind.STRING -> ConfigValueType.STRING
        PrimitiveKind.INT, PrimitiveKind.SHORT, PrimitiveKind.BYTE -> ConfigValueType.INT
        PrimitiveKind.LONG -> ConfigValueType.LONG
        PrimitiveKind.DOUBLE, PrimitiveKind.FLOAT -> ConfigValueType.DOUBLE
        PrimitiveKind.BOOLEAN -> ConfigValueType.BOOLEAN
        else -> ConfigValueType.STRING
    }

private fun readConfigDoc(
    ownerClass: Class<*>,
    propertyName: String,
): ConfigDoc? {
    fun fromField(): ConfigDoc? =
        try {
            ownerClass.getDeclaredField(propertyName).getAnnotation(ConfigDoc::class.java)
        } catch (_: NoSuchFieldException) {
            null
        }

    fun fromGetter(): ConfigDoc? {
        val getterName = "get${propertyName.replaceFirstChar { it.uppercase() }}"
        return try {
            ownerClass.getDeclaredMethod(getterName).getAnnotation(ConfigDoc::class.java)
        } catch (_: NoSuchMethodException) {
            null
        }
    }

    fun fromConstructors(): ConfigDoc? {
        for (constructor in ownerClass.constructors) {
            val param = constructor.parameters.firstOrNull { it.name == propertyName } ?: continue
            val ann = param.getAnnotation(ConfigDoc::class.java)
            if (ann != null) return ann
        }
        return null
    }

    return fromField() ?: fromGetter() ?: fromConstructors()
}

/**
 * Build a map of property name -> ConfigDoc by matching primary constructor
 * parameter annotations by index to serial descriptor element names.
 * Kotlin bytecode doesn't preserve parameter names by default, so we match
 * the primary constructor (the one whose param count matches the class property count)
 * by position.
 */
private fun buildAnnotationCache(
    ownerClass: Class<*>,
    descriptor: SerialDescriptor,
): Map<String, ConfigDoc> {
    val cache = mutableMapOf<String, ConfigDoc>()
    val elementCount = descriptor.elementsCount

    // Find the primary constructor — has ConfigDoc annotations and matches element count
    val primaryConstructor =
        ownerClass.constructors.firstOrNull { c ->
            c.parameterCount == elementCount && c.parameters.any { it.isAnnotationPresent(ConfigDoc::class.java) }
        }

    if (primaryConstructor != null) {
        for (i in 0 until elementCount) {
            val name = descriptor.getElementName(i)
            val param = primaryConstructor.parameters[i]
            val ann = param.getAnnotation(ConfigDoc::class.java)
            if (ann != null) {
                cache[name] = ann
            }
        }
    }

    return cache
}

private fun resolveClass(serialName: String): Class<*>? {
    val className = serialName.removeSuffix("?")
    return try {
        Class.forName(className)
    } catch (_: ClassNotFoundException) {
        null
    }
}
