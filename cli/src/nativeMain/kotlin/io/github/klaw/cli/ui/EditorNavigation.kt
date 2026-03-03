package io.github.klaw.cli.ui

private const val STATUS_BAR_LINES = 2

internal fun moveCursor(
    state: EditorState,
    direction: Int,
): EditorState {
    val newIndex = findNextNonDivider(state.items, state.cursorIndex, direction)
    return adjustViewport(state.copy(cursorIndex = newIndex))
}

internal fun moveCursorPage(
    state: EditorState,
    direction: Int,
): EditorState {
    val delta = state.viewportHeight * direction
    val rawIndex = (state.cursorIndex + delta).coerceIn(0, state.items.lastIndex)
    val newIndex =
        if (state.items[rawIndex] is EditorItem.SectionDivider) {
            findNextNonDivider(state.items, rawIndex, direction)
        } else {
            rawIndex
        }
    return adjustViewport(state.copy(cursorIndex = newIndex))
}

internal fun findNextNonDivider(
    items: List<EditorItem>,
    fromIndex: Int,
    direction: Int,
): Int {
    var idx = fromIndex + direction
    while (idx in items.indices && items[idx] is EditorItem.SectionDivider) {
        idx += direction
    }
    return if (idx in items.indices) idx else fromIndex
}

internal fun adjustViewport(state: EditorState): EditorState {
    var top = state.viewportTop
    if (state.cursorIndex < top) {
        top = state.cursorIndex
    }
    val visibleRows = state.viewportHeight - STATUS_BAR_LINES
    if (visibleRows > 0 && state.cursorIndex >= top + visibleRows) {
        top = state.cursorIndex - visibleRows + 1
    }
    return state.copy(viewportTop = top)
}
