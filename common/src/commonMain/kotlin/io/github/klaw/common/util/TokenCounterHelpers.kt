package io.github.klaw.common.util

internal fun Char.isCjkOrKana(): Boolean =
    this in '\u3040'..'\u30FF' ||  // Hiragana + Katakana
    this in '\u3400'..'\u4DBF' ||  // CJK Extension A
    this in '\u4E00'..'\u9FFF' ||  // CJK Unified Ideographs
    this in '\uAC00'..'\uD7A3' ||  // Hangul Syllables
    this in '\uF900'..'\uFAFF'     // CJK Compatibility Ideographs

/**
 * Platform-independent approximation of token count.
 * CJK/Kana: ~1.5 tokens/char (formula: (n*3+1)/2)
 * Other text: ~2/7 tokens/char (formula: (n*2+6)/7)
 */
internal fun computeApproximateTokenCount(text: String): Int {
    if (text.isEmpty()) return 0
    var cjk = 0
    var other = 0
    for (c in text) {
        if (c.isCjkOrKana()) cjk++ else other++
    }
    return (cjk * 3 + 1) / 2 + (other * 2 + 6) / 7
}
