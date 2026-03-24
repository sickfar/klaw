package io.github.klaw.cli.update

private const val SEMVER_PARTS = 3
private const val ORDER_SNAPSHOT = 0
private const val ORDER_ALPHA = 1
private const val ORDER_BETA = 2
private const val ORDER_RC = 3

/** Pre-release type ordering: SNAPSHOT < alpha < beta < rc < (release). */
private val PRE_RELEASE_ORDER = mapOf(
    "SNAPSHOT" to ORDER_SNAPSHOT,
    "alpha" to ORDER_ALPHA,
    "beta" to ORDER_BETA,
    "rc" to ORDER_RC,
)

/**
 * Parses a semver tag like "v0.1.0", "0.2.3", "v1.0.0-rc3", or "v1.0.0-SNAPSHOT".
 * Strips leading "v". Returns null for malformed input.
 */
internal fun parseSemVer(tag: String): SemVer? {
    if (tag.isBlank()) return null
    val cleaned = tag.removePrefix("v")

    val dashIndex = cleaned.indexOf('-')
    val versionPart: String
    val preRelease: String?

    if (dashIndex >= 0) {
        versionPart = cleaned.substring(0, dashIndex)
        preRelease = cleaned.substring(dashIndex + 1)
        if (preRelease.isEmpty()) return null
    } else {
        versionPart = cleaned
        preRelease = null
    }

    val parts = versionPart.split('.')
    if (parts.size != SEMVER_PARTS) return null
    val (major, minor, patch) = parseVersionParts(parts) ?: return null
    return SemVer(major, minor, patch, preRelease)
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
 * - Pre-release (rc, alpha, beta, SNAPSHOT) < stable release of the same version.
 * - Pre-release types are ordered: SNAPSHOT < alpha < beta < rc.
 * - Within the same type, numeric suffix is compared (rc1 < rc2 < rc3).
 * - Unparseable local = true (assume outdated).
 * - Unparseable remote = false (can't update to unparseable).
 */
internal fun isNewerVersion(
    localVersion: String,
    remoteTag: String,
): Boolean {
    val remote = parseSemVer(remoteTag) ?: return false
    val local = parseSemVer(localVersion) ?: return true

    // SNAPSHOT is always outdated — may have been rebuilt (like Maven SNAPSHOT policy)
    if (local.preRelease == "SNAPSHOT") return true

    val tupleCompare = compareTuples(
        Triple(local.major, local.minor, local.patch),
        Triple(remote.major, remote.minor, remote.patch),
    )
    if (tupleCompare != 0) return tupleCompare < 0

    // Same version tuple — compare pre-release
    return comparePreRelease(local.preRelease, remote.preRelease) < 0
}

/**
 * Compares pre-release strings. null means stable release (highest).
 * Returns negative if a < b, 0 if equal, positive if a > b.
 */
private fun comparePreRelease(a: String?, b: String?): Int {
    if (a == b) return 0
    if (a == null) return 1  // stable > any pre-release
    if (b == null) return -1 // any pre-release < stable

    val (aType, aNum) = splitPreRelease(a)
    val (bType, bNum) = splitPreRelease(b)

    val aOrder = PRE_RELEASE_ORDER[aType] ?: Int.MAX_VALUE
    val bOrder = PRE_RELEASE_ORDER[bType] ?: Int.MAX_VALUE

    if (aOrder != bOrder) return aOrder.compareTo(bOrder)
    return aNum.compareTo(bNum)
}

/** Splits "rc3" into ("rc", 3), "SNAPSHOT" into ("SNAPSHOT", 0). */
private fun splitPreRelease(pre: String): Pair<String, Int> {
    val idx = pre.indexOfFirst { it.isDigit() }
    if (idx < 0) return Pair(pre, 0)
    val type = pre.substring(0, idx)
    val num = pre.substring(idx).toIntOrNull() ?: 0
    return Pair(type, num)
}

private fun compareTuples(
    a: Triple<Int, Int, Int>,
    b: Triple<Int, Int, Int>,
): Int {
    if (a.first != b.first) return a.first.compareTo(b.first)
    if (a.second != b.second) return a.second.compareTo(b.second)
    return a.third.compareTo(b.third)
}
