package io.github.klaw.cli.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.access
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fwrite
import platform.posix.lstat
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.unlink

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
internal fun runCommandOutput(command: String): String? {
    val pipe = platform.posix.popen(command, "r") ?: return null
    val sb = StringBuilder()
    val buf = ByteArray(4096)
    buf.usePinned { pinned ->
        while (true) {
            val n = fread(pinned.addressOf(0), 1.convert(), buf.size.convert(), pipe).toInt()
            if (n <= 0) break
            sb.append(buf.decodeToString(0, n))
        }
    }
    val exitCode = platform.posix.pclose(pipe)
    return if (exitCode == 0) sb.toString().trimEnd() else null
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

@OptIn(ExperimentalForeignApi::class)
internal fun isSymlink(path: String): Boolean =
    memScoped {
        val statBuf = alloc<stat>()
        lstat(path, statBuf.ptr) == 0 && (statBuf.st_mode.toUInt() and S_IFMT.toUInt()) == S_IFLNK.toUInt()
    }

@OptIn(ExperimentalForeignApi::class)
internal fun deleteRecursively(path: String): Boolean {
    if (!fileExists(path) && !isSymlink(path)) return false
    // Symlinks are unlinked without following — prevents deleting outside the tree
    if (isSymlink(path)) return unlink(path) == 0
    if (isDirectory(path)) {
        val entries = listDirectory(path)
        for (entry in entries) {
            deleteRecursively("$path/$entry")
        }
        return rmdir(path) == 0
    }
    return unlink(path) == 0
}
