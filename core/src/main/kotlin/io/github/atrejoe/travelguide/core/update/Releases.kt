package io.github.atrejoe.travelguide.core.update

import io.github.atrejoe.travelguide.core.wiki.HttpFetcher
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * An app version as the releases name it: `0.8.2` (stable) or `0.9.0-alpha.3` (a merge to main).
 * Builds of pull requests (`-pr12.34`) and manual runs (`-dev.56`) have no place in that order,
 * so [parse] gives null for them and they never look for updates.
 */
data class AppVersion(val major: Int, val minor: Int, val patch: Int, val alpha: Int? = null) : Comparable<AppVersion> {
    val stable: Boolean get() = alpha == null

    override fun compareTo(other: AppVersion): Int = compareValuesBy(
        this, other,
        { it.major }, { it.minor }, { it.patch },
        // A release comes after all of its alphas: 0.9.0-alpha.7 < 0.9.0.
        { it.alpha ?: Int.MAX_VALUE },
    )

    override fun toString(): String = "$major.$minor.$patch" + (alpha?.let { "-alpha.$it" } ?: "")

    companion object {
        private val pattern = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-alpha\.(\d+))?$""")

        fun parse(text: String): AppVersion? = pattern.matchEntire(text.trim())?.let { m ->
            val (major, minor, patch, alpha) = m.destructured
            AppVersion(major.toInt(), minor.toInt(), patch.toInt(), alpha.toIntOrNull())
        }
    }
}

/** A published release on GitHub. [url] is its page, where the APK is. */
data class Release(val version: AppVersion, val tag: String, val url: String)

/** The app's releases on GitHub (issue: update notifications for installs from GitHub). */
class GitHubReleases(
    private val http: HttpFetcher,
    private val repository: String = "Atrejoe/TravelGuide",
) {
    /** The newest releases, drafts and unrecognised tags (such as "preview") left out. */
    suspend fun latest(): List<Release> = parse(http.get("https://api.github.com/repos/$repository/releases?per_page=30"))

    @Serializable
    private data class Entry(
        @SerialName("tag_name") val tag: String = "",
        @SerialName("html_url") val url: String = "",
        val draft: Boolean = false,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(body: String): List<Release> = json.decodeFromString<List<Entry>>(body)
            .filter { !it.draft && it.url.isNotEmpty() }
            .mapNotNull { e -> AppVersion.parse(e.tag)?.let { Release(it, e.tag, e.url) } }

        /**
         * The release to offer to someone on [current], or null when they are up to date. A stable
         * version is only offered stable releases; an alpha follows every release, alphas included,
         * so it also moves on to the stable release its alphas lead up to.
         */
        fun newer(current: AppVersion, releases: List<Release>): Release? = releases
            .filter { it.version > current && (it.version.stable || !current.stable) }
            .maxByOrNull { it.version }
    }
}
