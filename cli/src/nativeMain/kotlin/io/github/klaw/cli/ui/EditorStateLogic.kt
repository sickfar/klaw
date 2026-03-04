package io.github.klaw.cli.ui

import io.github.klaw.common.config.getByPath
import io.github.klaw.common.config.removeByPath
import io.github.klaw.common.config.setByPath
import kotlinx.serialization.json.JsonObject

internal fun processEvent(
    state: EditorState,
    event: EditorEvent,
): EditorState =
    when (state.editMode) {
        is EditMode.Navigation -> processNavigationEvent(state, event)
        is EditMode.InlineEdit -> processInlineEditEvent(state, event)
        is EditMode.KeyNameInput -> processKeyNameInputEvent(state, event, state.editMode)
        is EditMode.ConfirmDelete -> processConfirmDeleteEvent(state, event, state.editMode)
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
        EditorEvent.Add -> handleAddOnCurrentItem(state)
        EditorEvent.Delete -> handleDeleteOnCurrentItem(state)
        else -> state
    }

private fun handleAddOnCurrentItem(state: EditorState): EditorState {
    val item = state.items.getOrNull(state.cursorIndex) ?: return state
    return when (item) {
        is EditorItem.MapSectionHeader -> {
            state.copy(editMode = EditMode.KeyNameInput(buffer = "", mapPath = item.mapPath))
        }

        else -> {
            state
        }
    }
}

private fun handleDeleteOnCurrentItem(state: EditorState): EditorState {
    val item = state.items.getOrNull(state.cursorIndex) ?: return state
    return when (item) {
        is EditorItem.MapKeyHeader -> {
            state.copy(editMode = EditMode.ConfirmDelete(mapPath = item.mapPath, key = item.key))
        }

        else -> {
            state
        }
    }
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

private fun processKeyNameInputEvent(
    state: EditorState,
    event: EditorEvent,
    mode: EditMode.KeyNameInput,
): EditorState =
    when (event) {
        is EditorEvent.Char -> {
            if (event.c != '.' && !event.c.isWhitespace()) {
                state.copy(editMode = mode.copy(buffer = mode.buffer + event.c))
            } else {
                state
            }
        }

        EditorEvent.Backspace -> {
            val newBuffer = if (mode.buffer.isNotEmpty()) mode.buffer.dropLast(1) else mode.buffer
            state.copy(editMode = mode.copy(buffer = newBuffer))
        }

        EditorEvent.Enter -> {
            if (mode.buffer.isEmpty()) return state
            val mapElement = state.config.getByPath(mode.mapPath)
            if (mapElement is JsonObject && mapElement.containsKey(mode.buffer)) return state
            val newConfig = state.config.setByPath("${mode.mapPath}.${mode.buffer}", JsonObject(emptyMap()))
            val newItems = buildItems(state.descriptors, newConfig)
            state.copy(
                config = newConfig,
                items = newItems,
                editMode = EditMode.Navigation,
                modified = true,
                cursorIndex = state.cursorIndex.coerceAtMost(newItems.lastIndex.coerceAtLeast(0)),
            )
        }

        EditorEvent.Escape -> {
            state.copy(editMode = EditMode.Navigation)
        }

        else -> {
            state
        }
    }

private fun processConfirmDeleteEvent(
    state: EditorState,
    event: EditorEvent,
    mode: EditMode.ConfirmDelete,
): EditorState =
    when (event) {
        EditorEvent.Delete -> {
            val newConfig = state.config.removeByPath("${mode.mapPath}.${mode.key}")
            val newItems = buildItems(state.descriptors, newConfig)
            state.copy(
                config = newConfig,
                items = newItems,
                editMode = EditMode.Navigation,
                modified = true,
                cursorIndex = state.cursorIndex.coerceAtMost(newItems.lastIndex.coerceAtLeast(0)),
            )
        }

        EditorEvent.Escape -> {
            state.copy(editMode = EditMode.Navigation)
        }

        else -> {
            state
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
