package io.github.atrejoe.travelguide.core

import io.github.atrejoe.travelguide.core.update.AppVersion
import io.github.atrejoe.travelguide.core.update.GitHubReleases
import io.github.atrejoe.travelguide.core.update.Release
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReleasesTest {
    private fun v(text: String) = AppVersion.parse(text)!!
    private fun release(tag: String) = Release(v(tag), tag, "https://github.com/Atrejoe/TravelGuide/releases/tag/$tag")

    @Test
    fun `versions are read from release names and tags`() {
        assertEquals(AppVersion(0, 8, 2), AppVersion.parse("0.8.2"))
        assertEquals(AppVersion(0, 9, 0, 3), AppVersion.parse("v0.9.0-alpha.3"))
        assertNull(AppVersion.parse("0.9.0-pr105.221"))
        assertNull(AppVersion.parse("0.9.0-dev.220"))
        assertNull(AppVersion.parse("preview"))
    }

    @Test
    fun `a release comes after its alphas`() {
        assertTrue(v("0.9.0-alpha.2") < v("0.9.0-alpha.10"))
        assertTrue(v("0.9.0-alpha.10") < v("0.9.0"))
        assertTrue(v("0.8.2") < v("0.9.0-alpha.1"))
        assertTrue(v("0.10.0") > v("0.9.9"))
    }

    private val releases = listOf("v0.8.1", "v0.8.2", "v0.9.0-alpha.1", "v0.9.0-alpha.2").map { release(it) }

    @Test
    fun `a stable version is offered the next stable release only`() {
        assertEquals("v0.8.2", GitHubReleases.newer(v("0.8.1"), releases)?.tag)
        assertNull(GitHubReleases.newer(v("0.8.2"), releases))
    }

    @Test
    fun `an alpha follows every release`() {
        assertEquals("v0.9.0-alpha.2", GitHubReleases.newer(v("0.9.0-alpha.1"), releases)?.tag)
        assertNull(GitHubReleases.newer(v("0.9.0-alpha.2"), releases))
        assertEquals("v0.9.0", GitHubReleases.newer(v("0.9.0-alpha.2"), releases + release("v0.9.0"))?.tag)
    }

    @Test
    fun `GitHub's answer is read, drafts and other tags left out`() {
        val body = """
            [
              {"tag_name": "v0.9.0-alpha.2", "html_url": "https://github.com/a/b/releases/tag/v0.9.0-alpha.2", "draft": false, "prerelease": true},
              {"tag_name": "v0.9.0", "html_url": "https://github.com/a/b/releases/tag/v0.9.0", "draft": true},
              {"tag_name": "preview", "html_url": "https://github.com/a/b/releases/tag/preview", "draft": false},
              {"tag_name": "v0.8.2", "html_url": "https://github.com/a/b/releases/tag/v0.8.2", "draft": false, "assets": []}
            ]
        """.trimIndent()
        assertEquals(listOf("v0.9.0-alpha.2", "v0.8.2"), GitHubReleases.parse(body).map { it.tag })
    }
}
