package io.github.klaw.cli.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.access
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.opendir
import platform.posix.readdir

@OptIn(ExperimentalForeignApi::class)
internal fun fileExists(path: String): Boolean = access(path, F_OK) == 0

@OptIn(ExperimentalForeignApi::class)
internal fun isDirectory(path: String): Boolean {
    val dir = opendir(path) ?: return false
    closedir(dir)
    return true
}

@OptIn(ExperimentalForeignApi::class)
internal fun readFileText(path: String): String? {
    val file = fopen(path, "r") ?: return null
    val sb = StringBuilder()
    val buf = ByteArray(8192)
    buf.usePinned { pinned ->
        while (true) {
            val n = fread(pinned.addressOf(0), 1.convert(), buf.size.convert(), file).toInt()
            if (n <= 0) break
            sb.append(buf.decodeToString(0, n))
        }
    }
    fclose(file)
    return sb.toString()
}

@OptIn(ExperimentalForeignApi::class)
internal fun writeFileText(
    path: String,
    content: String,
) {
    val file = fopen(path, "w") ?: return
    val bytes = content.encodeToByteArray()
    bytes.usePinned { pinned ->
        fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
    }
    fclose(file)
}

@OptIn(ExperimentalForeignApi::class)
internal fun listDirectory(path: String): List<String> {
    val dir = opendir(path) ?: return emptyList()
    val names = mutableListOf<String>()
    try {
        while (true) {
            val entry = readdir(dir) ?: break
            val name = entry.pointed.d_name.toKString()
            if (name != "." && name != "..") names += name
        }
    } finally {
        closedir(dir)
    }
    return names
}
