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
    val expanded = expandWildcardDescriptors(descriptors, config)
    val editable =
        expanded.filter { d ->
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
        val item =
            EditorItem.Property(
                descriptor = d,
                isExplicit = isExplicit,
                displayValue = displayValue,
            )
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

internal fun processEvent(
    state: EditorState,
    event: EditorEvent,
): EditorState =
    when (state.editMode) {
        is EditMode.Navigation -> processNavigationEvent(state, event)
        is EditMode.InlineEdit -> processInlineEditEvent(state, event)
    }

private fun processNavigationEvent(
    state: EditorState,
    event: EditorEvent,
): EditorState =
    when (event) {
        EditorEvent.MoveUp -> moveCursor(state, -1)
        EditorEvent.MoveDown -> moveCursor(state, 1)
        EditorEvent.MoveLeft -> handleLeftRight(state, -1)
        EditorEvent.MoveRight -> handleLeftRight(state, 1)
        EditorEvent.Enter -> handleEnter(state)
        EditorEvent.Save -> state.copy(pendingAction = EditorAction.SAVE)
        EditorEvent.Quit -> state.copy(pendingAction = EditorAction.QUIT)
        EditorEvent.PageUp -> moveCursorPage(state, -1)
        EditorEvent.PageDown -> moveCursorPage(state, 1)
        else -> state
    }

private fun processInlineEditEvent(
    state: EditorState,
    event: EditorEvent,
): EditorState {
    val edit = state.editMode as EditMode.InlineEdit
    return when (event) {
        is EditorEvent.Char -> {
            state.copy(
                editMode =
                    EditMode.InlineEdit(
                        buffer = edit.buffer + event.c,
                        originalValue = edit.originalValue,
                    ),
            )
        }

        EditorEvent.Backspace -> {
            handleInlineBackspace(state, edit)
        }

        EditorEvent.Enter -> {
            commitInlineEdit(state, edit)
        }

        EditorEvent.Escape -> {
            state.copy(editMode = EditMode.Navigation)
        }

        EditorEvent.Save -> {
            appendCommandChar(state, edit, 's')
        }

        EditorEvent.Quit -> {
            appendCommandChar(state, edit, 'q')
        }

        EditorEvent.Delete -> {
            appendCommandChar(state, edit, 'd')
        }

        EditorEvent.Add -> {
            appendCommandChar(state, edit, 'a')
        }

        else -> {
            state
        }
    }
}

private fun handleInlineBackspace(
    state: EditorState,
    edit: EditMode.InlineEdit,
): EditorState {
    val newBuffer = if (edit.buffer.isNotEmpty()) edit.buffer.dropLast(1) else edit.buffer
    return state.copy(
        editMode = EditMode.InlineEdit(buffer = newBuffer, originalValue = edit.originalValue),
    )
}

private fun appendCommandChar(
    state: EditorState,
    edit: EditMode.InlineEdit,
    c: Char,
): EditorState =
    state.copy(
        editMode =
            EditMode.InlineEdit(
                buffer = edit.buffer + c,
                originalValue = edit.originalValue,
            ),
    )
