package io.github.klaw.cli

import kotlin.test.Test
import kotlin.test.assertTrue

class BuildConfigTest {
    @Test
    fun `VERSION is not blank`() {
        assertTrue(BuildConfig.VERSION.isNotBlank(), "BuildConfig.VERSION should not be blank")
    }

    @Test
    fun `GITHUB_OWNER is sickfar`() {
        assertTrue(BuildConfig.GITHUB_OWNER == "sickfar", "GITHUB_OWNER should be 'sickfar'")
    }

    @Test
    fun `GITHUB_REPO is klaw`() {
        assertTrue(BuildConfig.GITHUB_REPO == "klaw", "GITHUB_REPO should be 'klaw'")
    }
}
