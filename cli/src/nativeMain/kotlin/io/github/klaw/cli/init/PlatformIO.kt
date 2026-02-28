package io.github.klaw.cli.init

/** Creates a directory with 0755 permissions. Errors are silently ignored. */
internal expect fun mkdirMode755(path: String)

/** Sets read-write permissions (0600) on the file at [path]. */
internal expect fun chmodReadWrite(path: String)

/** Sets 0777 permissions on the directory at [path] so container users can write. */
internal expect fun chmodWorldRwx(path: String)
