package io.github.klaw.cli.chat

internal class KeyParser {
    private var state = 0
    private val utf8Buf = ByteArray(4)
    private var utf8Pos = 0
    private var utf8Expected = 0

    @Suppress("CyclomaticComplexMethod")
    fun feed(byte: Int): ChatEvent? =
        when (state) {
            0 -> {
                feedNormal(byte)
            }

            1 -> {
                feedAfterEsc(byte)
            }

            2 -> {
                feedAfterCsi(byte)
            }

            3 -> {
                feedAfterCsi3(byte)
            }

            4 -> {
                feedUtf8(byte)
            }

            else -> {
                state = 0
                null
            }
        }

    @Suppress("CyclomaticComplexMethod")
    private fun feedNormal(byte: Int): ChatEvent? =
        when (byte) {
            0x1B -> {
                state = 1
                null
            }

            3 -> {
                ChatEvent.Quit
            }

            127 -> {
                ChatEvent.Backspace
            }

            13, 10 -> {
                ChatEvent.Enter
            }

            in 32..0x7F -> {
                ChatEvent.KeyPressed(byte.toChar().toString())
            }

            in 0xC0..0xDF -> {
                startUtf8(byte, 2)
            }

            in 0xE0..0xEF -> {
                startUtf8(byte, 3)
            }

            in 0xF0..0xF7 -> {
                startUtf8(byte, 4)
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
        state = 4
        return null
    }

    private fun feedUtf8(byte: Int): ChatEvent? {
        if (byte in 0x80..0xBF) {
            utf8Buf[utf8Pos] = byte.toByte()
            utf8Pos++
            if (utf8Pos == utf8Expected) {
                state = 0
                val decoded = utf8Buf.decodeToString(0, utf8Pos)
                return ChatEvent.KeyPressed(decoded)
            }
            return null
        }
        // Invalid continuation — reset and re-feed
        state = 0
        return feedNormal(byte)
    }

    private fun feedAfterEsc(byte: Int): ChatEvent? {
        state = 0
        return when (byte) {
            0x5B -> {
                state = 2
                null
            }

            13, 10 -> {
                ChatEvent.NewLine
            }

            else -> {
                null
            }
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private fun feedAfterCsi(byte: Int): ChatEvent? {
        state = 0
        return when (byte) {
            65 -> {
                ChatEvent.ArrowKey(ChatEvent.ArrowKey.Direction.UP)
            }

            66 -> {
                ChatEvent.ArrowKey(ChatEvent.ArrowKey.Direction.DOWN)
            }

            67 -> {
                ChatEvent.ArrowKey(ChatEvent.ArrowKey.Direction.RIGHT)
            }

            68 -> {
                ChatEvent.ArrowKey(ChatEvent.ArrowKey.Direction.LEFT)
            }

            72 -> {
                ChatEvent.Home
            }

            70 -> {
                ChatEvent.End
            }

            51 -> {
                state = 3
                null
            }

            else -> {
                null
            }
        }
    }

    private fun feedAfterCsi3(byte: Int): ChatEvent? {
        state = 0
        return when (byte) {
            126 -> ChatEvent.Delete
            else -> null
        }
    }
}
