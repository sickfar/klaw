package io.github.klaw.cli.ui

import io.github.klaw.common.config.ConfigPropertyDescriptor
import kotlinx.serialization.json.JsonObject

internal sealed class EditMode {
    data object Navigation : EditMode()

    data class InlineEdit(
        val buffer: String,
        val originalValue: String,
    ) : EditMode()

    /** Prompting for a new map key name at the bottom of the screen. */
    data class KeyNameInput(
        val buffer: String,
        val mapPath: String,
    ) : EditMode()

    /** Confirming deletion of a map key (press D again to confirm, Esc to cancel). */
    data class ConfirmDelete(
        val mapPath: String,
        val key: String,
    ) : EditMode()
}

internal enum class EditorAction { NONE, SAVE, QUIT }

internal sealed class EditorItem {
    data class Property(
        val descriptor: ConfigPropertyDescriptor,
        val isExplicit: Boolean,
        val displayValue: String,
        val displayPath: String = descriptor.path,
    ) : EditorItem()

    data class SectionDivider(
        val label: String,
    ) : EditorItem()

    /** Header row for a whole map section, e.g. "── providers ──── [A]dd". */
    data class MapSectionHeader(
        val mapPath: String,
        val wildcardPath: String,
    ) : EditorItem()

    /** Header row for one key within a map section, e.g. "  ▸ zai ─── [D]". */
    data class MapKeyHeader(
        val mapPath: String,
        val key: String,
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
    val descriptors: List<ConfigPropertyDescriptor>,
    val pendingAction: EditorAction = EditorAction.NONE,
)
