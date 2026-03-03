package io.github.klaw.cli.ui

import io.github.klaw.common.config.ConfigPropertyDescriptor
import kotlinx.serialization.json.JsonObject

internal sealed class EditMode {
    data object Navigation : EditMode()

    data class InlineEdit(
        val buffer: String,
        val originalValue: String,
    ) : EditMode()
}

internal enum class EditorAction { NONE, SAVE, QUIT }

internal sealed class EditorItem {
    data class Property(
        val descriptor: ConfigPropertyDescriptor,
        val isExplicit: Boolean,
        val displayValue: String,
    ) : EditorItem()

    data class SectionDivider(
        val label: String,
    ) : EditorItem()
}

internal data class EditorState(
    val items: List<EditorItem>,
    val cursorIndex: Int,
    val viewportTop: Int,
    val viewportHeight: Int,
    val editMode: EditMode,
    val modified: Boolean,
    val config: JsonObject,
    val pendingAction: EditorAction = EditorAction.NONE,
)
