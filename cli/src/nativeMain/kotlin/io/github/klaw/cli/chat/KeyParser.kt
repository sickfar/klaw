package io.github.klaw.cli.chat

// Terminal byte constants
private const val ESC = 0x1B // ESC — starts escape sequences
private const val CSI_START = 0x5B // '[' — starts CSI sequence after ESC
private const val CTRL_C = 3 // Ctrl+C
private const val DEL = 127 // Backspace/DEL
private const val CR = 13 // Carriage Return
private const val LF = 10 // Line Feed
private const val SPACE = 32 // Space (first printable ASCII)
private const val ASCII_END = 0x7F // Last 7-bit ASCII

// VT100 CSI sequences (ESC [ <byte>)
private const val CSI_UP = 65 // ESC [ A — cursor up
private const val CSI_DOWN = 66 // ESC [ B — cursor down
private const val CSI_RIGHT = 67 // ESC [ C — cursor right
private const val CSI_LEFT = 68 // ESC [ D — cursor left
private const val CSI_HOME = 72 // ESC [ H — home
private const val CSI_END = 70 // ESC [ F — end
private const val CSI_DEL_INTRO = 51 // ESC [ 3 — intro for Delete (ESC [ 3 ~)
private const val CSI_DEL_SUFFIX = 126 // ESC [ 3 ~ — Delete key

// UTF-8 byte ranges
private const val UTF8_CONT_MIN = 0x80 // continuation byte min
private const val UTF8_CONT_MAX = 0xBF // continuation byte max
private const val UTF8_2BYTE_MIN = 0xC0 // 2-byte sequence leader min
private const val UTF8_2BYTE_MAX = 0xDF // 2-byte sequence leader max
private const val UTF8_3BYTE_MIN = 0xE0 // 3-byte sequence leader min
private const val UTF8_3BYTE_MAX = 0xEF // 3-byte sequence leader max
private const val UTF8_4BYTE_MIN = 0xF0 // 4-byte sequence leader min
private const val UTF8_4BYTE_MAX = 0xF7 // 4-byte sequence leader max

// UTF-8 sequence lengths
private const val UTF8_MAX_BYTES = 4 // max bytes in any UTF-8 sequence
private const val UTF8_3BYTE_LEN = 3 // 3-byte sequence expected continuation count

// Parser states
private const val STATE_NORMAL = 0
private const val STATE_AFTER_ESC = 1
private const val STATE_AFTER_CSI = 2
private const val STATE_AFTER_CSI3 = 3
private const val STATE_UTF8 = 4

internal class KeyParser {
    private var state = STATE_NORMAL
    private val utf8Buf = ByteArray(UTF8_MAX_BYTES)
    private var utf8Pos = 0
    private var utf8Expected = 0

    @Suppress("CyclomaticComplexMethod")
    fun feed(byte: Int): ChatEvent? =
        when (state) {
            STATE_NORMAL -> {
                feedNormal(byte)
            }

            STATE_AFTER_ESC -> {
                feedAfterEsc(byte)
            }

            STATE_AFTER_CSI -> {
                feedAfterCsi(byte)
            }

            STATE_AFTER_CSI3 -> {
                feedAfterCsi3(byte)
            }

            STATE_UTF8 -> {
                feedUtf8(byte)
            }

            else -> {
                state = STATE_NORMAL
                null
            }
        }

    @Suppress("CyclomaticComplexMethod")
    private fun feedNormal(byte: Int): ChatEvent? =
        when (byte) {
            ESC -> {
                state = STATE_AFTER_ESC
                null
            }

            CTRL_C -> {
                ChatEvent.Quit
            }

            DEL -> {
                ChatEvent.Backspace
            }

            CR, LF -> {
                ChatEvent.Enter
            }

            in SPACE..ASCII_END -> {
                ChatEvent.KeyPressed(byte.toChar().toString())
            }

            in UTF8_2BYTE_MIN..UTF8_2BYTE_MAX -> {
                startUtf8(byte, 2)
            }

            in UTF8_3BYTE_MIN..UTF8_3BYTE_MAX -> {
                startUtf8(byte, UTF8_3BYTE_LEN)
            }

            in UTF8_4BYTE_MIN..UTF8_4BYTE_MAX -> {
                startUtf8(byte, UTF8_MAX_BYTES)
            }

            else -> {
                null
            }
        }

    private fun startUtf8(
        byte: Int,
        expected: Int,
    ): ChatEvent? {
        utf8Buf[0] = byte.toByte()
        utf8Pos = 1
        utf8Expected = expected
        state = STATE_UTF8
        return null
    }

    private fun feedUtf8(byte: Int): ChatEvent? {
        if (byte in UTF8_CONT_MIN..UTF8_CONT_MAX) {
            utf8Buf[utf8Pos] = byte.toByte()
            utf8Pos++
            if (utf8Pos == utf8Expected) {
                state = STATE_NORMAL
                val decoded = utf8Buf.decodeToString(0, utf8Pos)
                return ChatEvent.KeyPressed(decoded)
            }
            return null
        }
        // Invalid continuation — reset and re-feed
        state = STATE_NORMAL
        return feedNormal(byte)
    }

    private fun feedAfterEsc(byte: Int): ChatEvent? {
        state = STATE_NORMAL
        return when (byte) {
            CSI_START -> {
                state = STATE_AFTER_CSI
                null
            }

            CR, LF -> {
                ChatEvent.NewLine
            }

            else -> {
                null
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun feedAfterCsi(byte: Int): ChatEvent? {
        state = STATE_NORMAL
        return when (byte) {
            CSI_UP -> {
                ChatEvent.ArrowKey(ChatEvent.ArrowKey.Direction.UP)
            }

            CSI_DOWN -> {
                ChatEvent.ArrowKey(ChatEvent.ArrowKey.Direction.DOWN)
            }

            CSI_RIGHT -> {
                ChatEvent.ArrowKey(ChatEvent.ArrowKey.Direction.RIGHT)
            }

            CSI_LEFT -> {
                ChatEvent.ArrowKey(ChatEvent.ArrowKey.Direction.LEFT)
            }

            CSI_HOME -> {
                ChatEvent.Home
            }

            CSI_END -> {
                ChatEvent.End
            }

            CSI_DEL_INTRO -> {
                state = STATE_AFTER_CSI3
                null
            }

            else -> {
                null
            }
        }
    }

    private fun feedAfterCsi3(byte: Int): ChatEvent? {
        state = STATE_NORMAL
        return when (byte) {
            CSI_DEL_SUFFIX -> ChatEvent.Delete
            else -> null
        }
    }
}
