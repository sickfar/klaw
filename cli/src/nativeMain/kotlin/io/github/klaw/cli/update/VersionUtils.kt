package io.github.klaw.cli.update

private const val SEMVER_PARTS = 3

/**
 * Parses a semver tag like "v0.1.0", "0.2.3", or "v1.0.0-SNAPSHOT".
 * Strips leading "v". Returns null for malformed input.
 */
internal fun parseSemVer(tag: String): SemVer? {
    if (tag.isBlank()) return null
    val cleaned = tag.removePrefix("v")
    val isSnapshot = cleaned.endsWith("-SNAPSHOT")
    val versionPart = if (isSnapshot) cleaned.removeSuffix("-SNAPSHOT") else cleaned
    val parts = versionPart.split('.')
    if (parts.size != SEMVER_PARTS) return null
    val (major, minor, patch) = parseVersionParts(parts) ?: return null
    return SemVer(major, minor, patch, isSnapshot)
}

private fun parseVersionParts(parts: List<String>): Triple<Int, Int, Int>? {
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].toIntOrNull() ?: return null
    val patch = parts[2].toIntOrNull() ?: return null
    if (major < 0 || minor < 0 || patch < 0) return null
    return Triple(major, minor, patch)
}

/**
 * Returns true if [remoteTag] represents a newer version than [localVersion].
 * - SNAPSHOT is always outdated vs non-SNAPSHOT release of the same or higher version.
 * - Unparseable local = true (assume outdated).
 * - Unparseable remote = false (can't update to unparseable).
 */
internal fun isNewerVersion(
    localVersion: String,
    remoteTag: String,
): Boolean {
    val remote = parseSemVer(remoteTag) ?: return false
    val local = parseSemVer(localVersion) ?: return true

    val localTuple = Triple(local.major, local.minor, local.patch)
    val remoteTuple = Triple(remote.major, remote.minor, remote.patch)

    // If tuples differ, compare them directly
    if (localTuple != remoteTuple) {
        return compareTuples(localTuple, remoteTuple) < 0
    }

    // Same version tuple: SNAPSHOT is outdated vs non-SNAPSHOT
    if (local.isSnapshot && !remote.isSnapshot) return true

    return false
}

private fun compareTuples(
    a: Triple<Int, Int, Int>,
    b: Triple<Int, Int, Int>,
): Int {
    if (a.first != b.first) return a.first.compareTo(b.first)
    if (a.second != b.second) return a.second.compareTo(b.second)
    return a.third.compareTo(b.third)
}
