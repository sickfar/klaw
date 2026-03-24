package io.github.klaw.cli

import io.github.klaw.common.paths.KlawPaths
import kotlin.test.Test
import kotlin.test.assertTrue

class InstallPathsTest {
    @Test
    fun `installDir is derived from KlawPaths data`() {
        val expected = "${KlawPaths.data}/bin"
        assertTrue(
            InstallPaths.installDir == expected,
            "Expected installDir=$expected, got ${InstallPaths.installDir}",
        )
    }

    @Test
    fun `symlinkDir ends with local-bin`() {
        assertTrue(
            InstallPaths.symlinkDir.endsWith("/.local/bin"),
            "Expected symlinkDir to end with /.local/bin, got ${InstallPaths.symlinkDir}",
        )
    }
}
