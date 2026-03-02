package io.github.klaw.cli.chat

internal class InputBuffer {
    private val buffer = StringBuilder()
    private var cursor = 0

    fun insert(char: Char) {
        buffer.insert(cursor, char)
        cursor++
    }

    fun insertText(s: String) {
        buffer.insert(cursor, s)
        cursor += s.length
    }

    fun insertNewline() {
        buffer.insert(cursor, '\n')
        cursor++
    }

    fun deleteBack() {
        if (cursor > 0) {
            cursor--
            buffer.deleteAt(cursor)
        }
    }

    fun deleteForward() {
        if (cursor < buffer.length) {
            buffer.deleteAt(cursor)
        }
    }

    fun moveLeft() {
        if (cursor > 0) cursor--
    }

    fun moveRight() {
        if (cursor < buffer.length) cursor++
    }

    fun moveHome() {
        val (row, _) = getCursorRowCol()
        val lines = getLines()
        var pos = 0
        for (i in 0 until row) {
            pos += lines[i].length + 1
        }
        cursor = pos
    }

    fun moveEnd() {
        val (row, _) = getCursorRowCol()
        val lines = getLines()
        var pos = 0
        for (i in 0 until row) {
            pos += lines[i].length + 1
        }
        pos += lines[row].length
        cursor = pos
    }

    fun moveUp() {
        val (row, col) = getCursorRowCol()
        if (row == 0) return
        val lines = getLines()
        val targetCol = minOf(col, lines[row - 1].length)
        var pos = 0
        for (i in 0 until row - 1) {
            pos += lines[i].length + 1
        }
        cursor = pos + targetCol
    }

    fun moveDown() {
        val (row, col) = getCursorRowCol()
        val lines = getLines()
        if (row >= lines.size - 1) return
        val targetCol = minOf(col, lines[row + 1].length)
        var pos = 0
        for (i in 0..row) {
            pos += lines[i].length + 1
        }
        cursor = pos + targetCol
    }

    fun submit(): String {
        val text = buffer.toString()
        buffer.clear()
        cursor = 0
        return text
    }

    fun getText(): String = buffer.toString()

    fun getCursor(): Int = cursor

    fun isEmpty(): Boolean = buffer.isEmpty()

    fun getLines(): List<String> = buffer.toString().split('\n')

    fun getCursorRowCol(): Pair<Int, Int> {
        val text = buffer.substring(0, cursor)
        val row = text.count { it == '\n' }
        val lastNewline = text.lastIndexOf('\n')
        val col = if (lastNewline == -1) cursor else cursor - lastNewline - 1
        return Pair(row, col)
    }
}
