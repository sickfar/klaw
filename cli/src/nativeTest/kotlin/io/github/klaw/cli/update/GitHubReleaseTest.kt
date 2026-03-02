package io.github.klaw.cli.update

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GitHubReleaseTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `parse valid release JSON`() {
        val payload =
            """
            {
                "tag_name": "v0.2.0",
                "prerelease": false,
                "draft": false,
                "assets": [
                    {
                        "name": "klaw-linuxArm64",
                        "browser_download_url": "https://github.com/sickfar/klaw/releases/download/v0.2.0/klaw-linuxArm64"
                    }
                ]
            }
            """.trimIndent()

        val release = json.decodeFromString(GitHubRelease.serializer(), payload)
        assertEquals("v0.2.0", release.tagName)
        assertFalse(release.prerelease)
        assertFalse(release.draft)
        assertEquals(1, release.assets.size)
        assertEquals("klaw-linuxArm64", release.assets[0].name)
        assertEquals(
            "https://github.com/sickfar/klaw/releases/download/v0.2.0/klaw-linuxArm64",
            release.assets[0].browserDownloadUrl,
        )
    }

    @Test
    fun `parse release with multiple assets`() {
        val payload =
            """
            {
                "tag_name": "v0.3.0",
                "prerelease": false,
                "draft": false,
                "assets": [
                    {
                        "name": "klaw-linuxArm64",
                        "browser_download_url": "https://example.com/klaw-linuxArm64"
                    },
                    {
                        "name": "klaw-macosArm64",
                        "browser_download_url": "https://example.com/klaw-macosArm64"
                    },
                    {
                        "name": "klaw-engine-0.3.0-all.jar",
                        "browser_download_url": "https://example.com/klaw-engine-0.3.0-all.jar"
                    }
                ]
            }
            """.trimIndent()

        val release = json.decodeFromString(GitHubRelease.serializer(), payload)
        assertEquals(3, release.assets.size)
        assertEquals("klaw-macosArm64", release.assets[1].name)
    }

    @Test
    fun `parse release with unknown fields ignores them`() {
        val payload =
            """
            {
                "tag_name": "v0.1.0",
                "prerelease": true,
                "draft": false,
                "assets": [],
                "html_url": "https://github.com/sickfar/klaw/releases/tag/v0.1.0",
                "id": 12345,
                "created_at": "2025-01-01T00:00:00Z",
                "body": "Release notes here"
            }
            """.trimIndent()

        val release = json.decodeFromString(GitHubRelease.serializer(), payload)
        assertEquals("v0.1.0", release.tagName)
        assertTrue(release.prerelease)
        assertEquals(0, release.assets.size)
    }

    @Test
    fun `parse asset with unknown fields ignores them`() {
        val payload =
            """
            {
                "tag_name": "v0.1.0",
                "prerelease": false,
                "draft": false,
                "assets": [
                    {
                        "name": "klaw-linuxArm64",
                        "browser_download_url": "https://example.com/klaw-linuxArm64",
                        "size": 12345678,
                        "content_type": "application/octet-stream",
                        "download_count": 42
                    }
                ]
            }
            """.trimIndent()

        val release = json.decodeFromString(GitHubRelease.serializer(), payload)
        assertEquals("klaw-linuxArm64", release.assets[0].name)
    }

    @Test
    fun `fake client returns pre-constructed release`() {
        val fakeRelease =
            GitHubRelease(
                tagName = "v1.0.0",
                assets =
                    listOf(
                        GitHubAsset("klaw-macosArm64", "https://example.com/klaw-macosArm64"),
                    ),
                prerelease = false,
                draft = false,
            )

        val client = FakeGitHubReleaseClient(fakeRelease)
        // Verify the fake returns expected data (non-suspend test)
        assertEquals("v1.0.0", client.latestRelease?.tagName)
        assertEquals(1, client.latestRelease?.assets?.size)
    }

    @Test
    fun `fake client returns null for no release`() {
        val client = FakeGitHubReleaseClient(null)
        assertEquals(null, client.latestRelease)
    }
}

/** Simple fake for testing code that depends on GitHubReleaseClient. */
private class FakeGitHubReleaseClient(
    val latestRelease: GitHubRelease?,
) : GitHubReleaseClient {
    override suspend fun fetchLatest(): GitHubRelease? = latestRelease

    override suspend fun fetchByTag(tag: String): GitHubRelease? = latestRelease?.takeIf { it.tagName == tag }
}
