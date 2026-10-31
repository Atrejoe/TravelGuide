package io.github.atrejoe.travelguide.core.guide

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.story.Poi

/** A story the guide told, remembered across trips (issue #84). */
data class ToldStory(
    val title: String,
    val lang: String,
    /** The Wikipedia page id in [lang]; places found elsewhere have their own negative ids. */
    val pageId: Long,
    /** Recognises the place in any language. */
    val wikidataId: String?,
    val timeMillis: Long,
    val url: String = "",
    /** The story as told, to show and play it again; empty for a place woven into another's story. */
    val sentences: List<String> = emptyList(),
    val point: GeoPoint? = null,
)

/** How long told stories are remembered. */
enum class HistoryRetention(val days: Int?) {
    /** Only during the trip: nothing is kept. */
    TRIP(0),
    WEEK(7),
    MONTH(30),
    THREE_MONTHS(91),
    YEAR(365),

    /** Until the history is erased. */
    ALWAYS(null),
    ;

    companion object {
        val DEFAULT = THREE_MONTHS
        fun byName(name: String?): HistoryRetention = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** What the guide remembers from earlier trips; it doesn't tell those places again. */
fun interface StoryHistory {
    fun wasTold(poi: Poi): Boolean
}

/**
 * The stories told, newest last, kept for as long as [retention] says and at most [maxStories]
 * of them. Pure bookkeeping: saving them is up to the app.
 */
class StoryLog(
    retention: HistoryRetention = HistoryRetention.DEFAULT,
    private val maxStories: Int = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : StoryHistory {
    private val stories = ArrayList<ToldStory>()
    private val pages = HashSet<String>()
    private val items = HashSet<String>()

    var retention: HistoryRetention = retention
        set(value) {
            field = value
            prune()
        }

    /** Oldest first. */
    val entries: List<ToldStory> get() = stories.toList()

    val size: Int get() = stories.size

    override fun wasTold(poi: Poi): Boolean =
        "${poi.lang}:${poi.pageId}" in pages || (poi.wikidataId != null && poi.wikidataId in items)

    /** Remembers [story]; nothing when only the trip is remembered. */
    fun add(story: ToldStory) {
        if (retention == HistoryRetention.TRIP) return
        stories += story
        prune()
    }

    /** Replaces everything with [loaded], e.g. read from a file, dropping what has expired. */
    fun restore(loaded: List<ToldStory>) {
        stories.clear()
        stories += loaded.sortedBy { it.timeMillis }
        prune()
    }

    fun clear() {
        stories.clear()
        reindex()
    }

    /** Drops what is older than [retention] allows, or beyond [maxStories]. */
    fun prune() {
        val days = retention.days
        if (days != null) {
            val oldest = clock() - days * DAY_MILLIS
            stories.removeAll { it.timeMillis < oldest }
        }
        if (stories.size > maxStories) stories.subList(0, stories.size - maxStories).clear()
        reindex()
    }

    private fun reindex() {
        pages.clear()
        items.clear()
        for (s in stories) {
            pages += "${s.lang}:${s.pageId}"
            s.wikidataId?.let { items += it }
        }
    }

    private companion object {
        const val DAY_MILLIS = 24 * 60 * 60 * 1000L
    }
}
