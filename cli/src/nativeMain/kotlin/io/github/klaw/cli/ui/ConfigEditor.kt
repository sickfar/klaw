package io.github.klaw.cli.ui

import io.github.klaw.common.config.ConfigPropertyDescriptor
import io.github.klaw.common.config.ConfigValueType
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.serialization.json.JsonObject
import platform.posix.fflush
import platform.posix.stdout

// ── Rendering ───────────────────────────────────────────────────────────

private const val ANSI_REVERSE = "\u001B[7m"
private const val ANSI_DIM = "\u001B[2m"
private const val ANSI_RESET = "\u001B[0m"
private const val DIVIDER_CHAR = "\u2500" // ─
private const val STATUS_BAR_LINES = 2

internal fun renderLine(
    item: EditorItem,
    isCursor: Boolean,
    editMode: EditMode,
    termWidth: Int,
): String =
    when (item) {
        is EditorItem.SectionDivider -> renderDividerLine(item, termWidth)
        is EditorItem.Property -> renderPropertyLine(item, isCursor, editMode, termWidth)
    }

private fun renderDividerLine(
    item: EditorItem.SectionDivider,
    termWidth: Int,
): String {
    val label = " ${item.label} "
    val sideWidth = (termWidth - label.length) / 2
    val leftPad = if (sideWidth > 0) DIVIDER_CHAR.repeat(sideWidth) else ""
    val rightPad =
        if (sideWidth > 0) {
            DIVIDER_CHAR.repeat(termWidth - sideWidth - label.length)
        } else {
            ""
        }
    return "$ANSI_DIM$leftPad$label$rightPad$ANSI_RESET"
}

private fun renderPropertyLine(
    item: EditorItem.Property,
    isCursor: Boolean,
    editMode: EditMode,
    termWidth: Int,
): String {
    val desc = item.descriptor
    val prefix = if (isCursor) ANSI_REVERSE else ""
    val suffix = if (isCursor) ANSI_RESET else ""
    val dimPrefix = if (!item.isExplicit && editMode !is EditMode.InlineEdit) ANSI_DIM else ""
    val dimSuffix = if (dimPrefix.isNotEmpty()) ANSI_RESET else ""

    val content =
        if (isCursor && editMode is EditMode.InlineEdit) {
            "${desc.path} = [${editMode.buffer}_]"
        } else if (desc.type == ConfigValueType.BOOLEAN || !desc.possibleValues.isNullOrEmpty()) {
            val arrows = "\u25C2 ${item.displayValue} \u25B8"
            "${desc.path} = $arrows"
        } else {
            "${desc.path} = ${item.displayValue}"
        }

    val truncated = if (content.length > termWidth) content.take(termWidth) else content
    return "$dimPrefix$prefix$truncated$suffix$dimSuffix"
}

// ── Interactive editor (terminal I/O) ───────────────────────────────────

@OptIn(ExperimentalForeignApi::class)
internal class ConfigEditor(
    private val descriptors: List<ConfigPropertyDescriptor>,
    private val config: JsonObject,
    private val printer: (String) -> Unit = { print(it) },
) {
    fun run(): JsonObject? {
        val editable =
            descriptors.filter { d ->
                d.type != ConfigValueType.MAP_SECTION && d.type != ConfigValueType.LIST_STRING
            }
        if (editable.isEmpty()) return null

        val items = buildItems(descriptors, config)
        var state =
            EditorState(
                items = items,
                cursorIndex = 0,
                viewportTop = 0,
                viewportHeight = TerminalRaw.getTerminalHeight(),
                editMode = EditMode.Navigation,
                modified = false,
                config = config,
            )

        var escState: EditorEscState = EditorEscState.None

        TerminalRaw.enableRawMode()
        try {
            renderScreen(state)
            while (true) {
                val byte = TerminalRaw.readByte()
                if (byte < 0) return null

                val (newEscState, event) = handleEditorByte(byte, escState)
                escState = newEscState
                if (event == null) continue

                state = processEvent(state, event)

                when (state.pendingAction) {
                    EditorAction.SAVE -> return state.config
                    EditorAction.QUIT -> return null
                    EditorAction.NONE -> Unit
                }

                renderScreen(state)
            }
        } finally {
            TerminalRaw.restoreTerminal()
            printer("\u001B[2J\u001B[H")
            fflush(stdout)
        }
    }

    private fun renderScreen(state: EditorState) {
        val sb = StringBuilder()
        sb.append("\u001B[H\u001B[2J")

        val termWidth = TerminalRaw.getTerminalWidth()
        val visibleRows = state.viewportHeight - STATUS_BAR_LINES
        val endIndex = minOf(state.viewportTop + visibleRows, state.items.size)

        for (i in state.viewportTop until endIndex) {
            sb.append(renderLine(state.items[i], i == state.cursorIndex, state.editMode, termWidth))
            sb.append("\r\n")
        }

        val rendered = endIndex - state.viewportTop
        for (i in rendered until visibleRows) {
            sb.append("\u001B[K\r\n")
        }

        sb.append(ANSI_REVERSE)
        val statusText = buildStatusText(state)
        sb.append(statusText.take(termWidth).padEnd(termWidth))
        sb.append("$ANSI_RESET\r\n")

        sb.append(buildHelpText(state).take(termWidth))

        printer(sb.toString())
        fflush(stdout)
    }

    private fun buildStatusText(state: EditorState): String {
        val modifiedMarker = if (state.modified) " [modified]" else ""
        val item = state.items.getOrNull(state.cursorIndex)
        val desc = if (item is EditorItem.Property) " | ${item.descriptor.description}" else ""
        return " Config Editor$modifiedMarker$desc"
    }

    private fun buildHelpText(state: EditorState): String =
        when (state.editMode) {
            is EditMode.Navigation -> " [S]ave  [Q]uit  Arrows:navigate  Enter:edit"
            is EditMode.InlineEdit -> " Enter:confirm  Esc:cancel"
        }
}
