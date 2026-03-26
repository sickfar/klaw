package io.github.klaw.cli.init

import io.github.klaw.cli.util.readFileText

internal object EnvReader {
    /**
     * Reads a `.env` file into a key-value map.
     *
     * Skips blank lines, comment lines (starting with `#`), and lines without `=`.
     * Splits on the first `=` only, so values may contain `=` characters.
     * Returns an empty map if the file does not exist.
     */
    fun read(path: String): Map<String, String> {
        val content = readFileText(path) ?: return emptyMap()
        val result = linkedMapOf<String, String>()
        for (line in content.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val eqIndex = line.indexOf('=')
            if (eqIndex < 0) continue
            val key = line.substring(0, eqIndex)
            val value = line.substring(eqIndex + 1)
            result[key] = value
        }
        return result
    }
}
