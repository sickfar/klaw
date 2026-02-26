package io.github.klaw.cli.init

import platform.posix.F_OK
import platform.posix.access

fun isInsideDocker(path: String = "/.dockerenv"): Boolean = access(path, F_OK) == 0
