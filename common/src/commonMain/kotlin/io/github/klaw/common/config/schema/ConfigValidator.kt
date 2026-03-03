package io.github.klaw.common.config.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

fun validateConfig(
    schema: JsonObject,
    config: JsonElement,
): List<ValidationError> {
    val errors = mutableListOf<ValidationError>()
    validate(schema, config, "", errors)
    return errors
}

private fun validate(
    schema: JsonObject,
    element: JsonElement,
    path: String,
    errors: MutableList<ValidationError>,
) {
    // Null is always OK for non-required fields (caller handles required checking)
    if (element is JsonNull) return

    val expectedType = schema["type"]?.jsonPrimitive?.contentOrNull

    // Type checking
    if (expectedType != null && !matchesType(element, expectedType)) {
        errors.add(ValidationError(path, "Expected type '$expectedType' but got ${elementTypeName(element)}"))
        return // don't recurse into wrong-type elements
    }

    // Numeric constraints
    if (element is JsonPrimitive && (expectedType == "integer" || expectedType == "number")) {
        checkNumericConstraints(schema, element, path, errors)
    }

    // Object validation
    if (expectedType == "object" && element is JsonObject) {
        validateObject(schema, element, path, errors)
    }

    // Array validation
    if (expectedType == "array" && element is JsonArray) {
        val itemSchema = schema["items"]?.jsonObject
        if (itemSchema != null) {
            element.forEachIndexed { index, item ->
                validate(itemSchema, item, "$path[$index]", errors)
            }
        }
    }
}

private fun validateObject(
    schema: JsonObject,
    obj: JsonObject,
    path: String,
    errors: MutableList<ValidationError>,
) {
    // Required fields
    val required = schema["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
    for (field in required) {
        if (field !in obj) {
            errors.add(ValidationError("$path.$field", "Required field is missing"))
        }
    }

    // Property schemas
    val properties = schema["properties"]?.jsonObject
    val addlProps = schema["additionalProperties"]
    val rejectUnknown = addlProps is JsonPrimitive && addlProps.content == "false"

    if (properties != null) {
        for ((key, value) in obj) {
            val propSchema = properties[key]?.jsonObject
            if (propSchema != null) {
                validate(propSchema, value, "$path.$key", errors)
            } else if (rejectUnknown) {
                errors.add(ValidationError("$path.$key", "Unknown property"))
            }
        }
    }

    validateAdditionalPropertiesSchema(addlProps, properties, obj, path, errors)
}

private fun validateAdditionalPropertiesSchema(
    addlProps: JsonElement?,
    properties: JsonObject?,
    obj: JsonObject,
    path: String,
    errors: MutableList<ValidationError>,
) {
    if (addlProps !is JsonObject) return
    if (properties == null) {
        // Map type — validate each value against the additionalProperties schema
        for ((key, value) in obj) {
            validate(addlProps, value, "$path.$key", errors)
        }
    } else {
        // Has both properties and additionalProperties — validate unknown keys
        for ((key, value) in obj) {
            if (key !in properties) {
                validate(addlProps, value, "$path.$key", errors)
            }
        }
    }
}

private fun checkNumericConstraints(
    schema: JsonObject,
    element: JsonPrimitive,
    path: String,
    errors: MutableList<ValidationError>,
) {
    val numValue = element.longOrNull?.toDouble() ?: element.doubleOrNull ?: return

    schema["minimum"]?.jsonPrimitive?.doubleOrNull?.let { min ->
        if (numValue < min) {
            errors.add(ValidationError(path, "Value $numValue is less than minimum $min"))
        }
    }

    schema["exclusiveMinimum"]?.jsonPrimitive?.doubleOrNull?.let { exMin ->
        if (numValue <= exMin) {
            errors.add(ValidationError(path, "Value $numValue must be greater than $exMin"))
        }
    }

    schema["maximum"]?.jsonPrimitive?.doubleOrNull?.let { max ->
        if (numValue > max) {
            errors.add(ValidationError(path, "Value $numValue is greater than maximum $max"))
        }
    }

    schema["exclusiveMaximum"]?.jsonPrimitive?.doubleOrNull?.let { exMax ->
        if (numValue >= exMax) {
            errors.add(ValidationError(path, "Value $numValue must be less than $exMax"))
        }
    }
}

private fun isNumericPrimitive(
    element: JsonPrimitive,
    type: String,
): Boolean {
    if (element.isString) return false
    return when (type) {
        "integer" -> element.longOrNull != null
        "number" -> element.doubleOrNull != null || element.longOrNull != null
        else -> false
    }
}

private fun matchesType(
    element: JsonElement,
    expectedType: String,
): Boolean =
    when (expectedType) {
        "object" -> {
            element is JsonObject
        }

        "array" -> {
            element is JsonArray
        }

        "string" -> {
            element is JsonPrimitive && element.isString
        }

        "integer", "number" -> {
            element is JsonPrimitive && isNumericPrimitive(element, expectedType)
        }

        "boolean" -> {
            element is JsonPrimitive && !element.isString &&
                element.content in listOf("true", "false")
        }

        else -> {
            true
        }
    }

private fun elementTypeName(element: JsonElement): String =
    when (element) {
        is JsonObject -> {
            "object"
        }

        is JsonArray -> {
            "array"
        }

        is JsonNull -> {
            "null"
        }

        is JsonPrimitive -> {
            when {
                element.isString -> "string"
                element.content in listOf("true", "false") -> "boolean"
                element.longOrNull != null -> "integer"
                element.doubleOrNull != null -> "number"
                else -> "unknown"
            }
        }
    }
