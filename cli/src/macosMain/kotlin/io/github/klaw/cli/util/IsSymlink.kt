package io.github.klaw.cli.util

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import platform.posix.S_IFLNK
import platform.posix.S_IFMT
import platform.posix.lstat
import platform.posix.stat

@OptIn(ExperimentalForeignApi::class)
internal actual fun isSymlink(path: String): Boolean =
    memScoped {
        val statBuf = alloc<stat>()
        lstat(path, statBuf.ptr) == 0 && (statBuf.st_mode.toUInt() and S_IFMT.toUInt()) == S_IFLNK.toUInt()
    }
