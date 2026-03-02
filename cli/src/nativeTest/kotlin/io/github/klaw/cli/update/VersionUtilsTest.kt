package io.github.klaw.cli.update

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VersionUtilsTest {
    // --- parseSemVer tests ---

    @Test
    fun `parseSemVer with v prefix`() {
        val result = parseSemVer("v1.2.3")
        assertEquals(SemVer(1, 2, 3), result)
    }

    @Test
    fun `parseSemVer without prefix`() {
        val result = parseSemVer("0.4.1")
        assertEquals(SemVer(0, 4, 1), result)
    }

    @Test
    fun `parseSemVer SNAPSHOT`() {
        val result = parseSemVer("v0.1.0-SNAPSHOT")
        assertEquals(SemVer(0, 1, 0, isSnapshot = true), result)
    }

    @Test
    fun `parseSemVer malformed returns null`() {
        assertNull(parseSemVer("abc"))
    }

    @Test
    fun `parseSemVer two-part returns null`() {
        assertNull(parseSemVer("1.0"))
    }

    @Test
    fun `parseSemVer empty string returns null`() {
        assertNull(parseSemVer(""))
    }

    @Test
    fun `parseSemVer non-numeric parts returns null`() {
        assertNull(parseSemVer("v1.x.3"))
    }

    @Test
    fun `parseSemVer negative numbers returns null`() {
        assertNull(parseSemVer("v-1.0.0"))
    }

    // --- isNewerVersion tests ---

    @Test
    fun `SNAPSHOT vs release is newer`() {
        assertTrue(isNewerVersion("0.1.0-SNAPSHOT", "v0.1.0"))
    }

    @Test
    fun `equal versions is not newer`() {
        assertFalse(isNewerVersion("0.1.0", "v0.1.0"))
    }

    @Test
    fun `older remote is not newer`() {
        assertFalse(isNewerVersion("0.2.0", "v0.1.0"))
    }

    @Test
    fun `newer remote is newer`() {
        assertTrue(isNewerVersion("0.1.0", "v0.2.0"))
    }

    @Test
    fun `both SNAPSHOT same version is not newer`() {
        assertFalse(isNewerVersion("0.1.0-SNAPSHOT", "v0.1.0-SNAPSHOT"))
    }

    @Test
    fun `both SNAPSHOT different versions compares tuples`() {
        assertTrue(isNewerVersion("0.1.0-SNAPSHOT", "v0.2.0-SNAPSHOT"))
    }

    @Test
    fun `unparseable local assumes outdated`() {
        assertTrue(isNewerVersion("garbage", "v0.1.0"))
    }

    @Test
    fun `unparseable remote returns false`() {
        assertFalse(isNewerVersion("0.1.0", "not-a-version"))
    }

    @Test
    fun `both unparseable returns false`() {
        assertFalse(isNewerVersion("garbage", "also-garbage"))
    }

    @Test
    fun `major version bump detected`() {
        assertTrue(isNewerVersion("0.9.9", "v1.0.0"))
    }

    @Test
    fun `minor version bump detected`() {
        assertTrue(isNewerVersion("0.1.9", "v0.2.0"))
    }

    @Test
    fun `patch version bump detected`() {
        assertTrue(isNewerVersion("0.1.0", "v0.1.1"))
    }
}
