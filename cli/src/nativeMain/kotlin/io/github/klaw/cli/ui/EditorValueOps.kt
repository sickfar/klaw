package io.github.klaw.cli.ui

import io.github.klaw.common.config.ConfigPropertyDescriptor
import io.github.klaw.common.config.ConfigValueType
import io.github.klaw.common.config.formatConfigValue
import io.github.klaw.common.config.getByPath
import io.github.klaw.common.config.parseConfigValue
import io.github.klaw.common.config.setByPath
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

internal fun handleLeftRight(
    state: EditorState,
    direction: Int,
): EditorState {
    val item = state.items.getOrNull(state.cursorIndex) ?: return state
    if (item !is EditorItem.Property) return state
    val desc = item.descriptor
    return when {
        desc.type == ConfigValueType.BOOLEAN -> toggleBoolean(state, desc)
        !desc.possibleValues.isNullOrEmpty() -> cycleEnum(state, desc, direction)
        else -> state
    }
}

internal fun toggleBoolean(
    state: EditorState,
    desc: ConfigPropertyDescriptor,
): EditorState {
    val currentValue = resolveCurrentValue(state.config, desc)
    val newJsonValue = JsonPrimitive(currentValue != "true")
    val newConfig = state.config.setByPath(desc.path, newJsonValue)
    return rebuildItems(state, newConfig)
}

internal fun cycleEnum(
    state: EditorState,
    desc: ConfigPropertyDescriptor,
    direction: Int,
): EditorState {
    val values = desc.possibleValues ?: return state
    val currentValue = resolveCurrentValue(state.config, desc)
    val currentIndex = values.indexOf(currentValue)
    val newIndex = if (currentIndex < 0) 0 else (currentIndex + direction + values.size) % values.size
    val newConfig = state.config.setByPath(desc.path, JsonPrimitive(values[newIndex]))
    return rebuildItems(state, newConfig)
}

internal fun handleEnter(state: EditorState): EditorState {
    val item = state.items.getOrNull(state.cursorIndex) ?: return state
    if (item !is EditorItem.Property) return state
    val desc = item.descriptor
    return when {
        desc.type == ConfigValueType.BOOLEAN -> {
            toggleBoolean(state, desc)
        }

        !desc.possibleValues.isNullOrEmpty() -> {
            cycleEnum(state, desc, 1)
        }

        else -> {
            val rawValue = resolveCurrentValue(state.config, desc)
            state.copy(editMode = EditMode.InlineEdit(buffer = rawValue, originalValue = rawValue))
        }
    }
}

internal fun commitInlineEdit(
    state: EditorState,
    edit: EditMode.InlineEdit,
): EditorState {
    val item = state.items.getOrNull(state.cursorIndex) ?: return state
    if (item !is EditorItem.Property) return state
    val parsed = parseConfigValue(edit.buffer, item.descriptor.type) ?: return state
    val newConfig = state.config.setByPath(item.descriptor.path, parsed)
    val newItems =
        buildItems(
            state.items.filterIsInstance<EditorItem.Property>().map { it.descriptor },
            newConfig,
        )
    return state.copy(config = newConfig, items = newItems, editMode = EditMode.Navigation, modified = true)
}

internal fun rebuildItems(
    state: EditorState,
    newConfig: JsonObject,
): EditorState {
    val newItems =
        buildItems(
            state.items.filterIsInstance<EditorItem.Property>().map { it.descriptor },
            newConfig,
        )
    return state.copy(config = newConfig, items = newItems, modified = true)
}

internal fun resolveCurrentValue(
    config: JsonObject,
    desc: ConfigPropertyDescriptor,
): String {
    val element = config.getByPath(desc.path)
    return if (element != null) formatConfigValue(element, desc.type, sensitive = false) else desc.defaultValue ?: ""
}
