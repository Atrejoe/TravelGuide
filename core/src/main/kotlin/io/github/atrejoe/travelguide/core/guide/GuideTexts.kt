package io.github.atrejoe.travelguide.core.guide

import java.util.Locale

/**
 * What the guide shows on screen about what it is doing (issue #65), in the app's language
 * (issue #92). [English] is the default; the app supplies the translations.
 */
interface GuideTexts {
    fun railways(): String
    fun roads(): String
    fun routeTowards(name: String): String
    fun searching(radius: String): String
    fun buildings(): String
    fun town(): String
    fun translating(title: String): String
    fun reading(title: String): String

    fun nextStoryIn(duration: String): String
    fun nothingYet(duration: String): String
    fun widerIn(radius: String, duration: String): String
    fun widest(radius: String): String

    /** "800 m", "2 km", "2.5 km". */
    fun radius(meters: Int): String = when {
        meters < 1_000 -> "$meters m"
        meters % 1_000 == 0 -> "${meters / 1_000} km"
        else -> "${"%.1f".format(Locale.ROOT, meters / 1_000.0)} km"
    }

    /** "20 s" or "2 min", rounded up. */
    fun duration(millis: Long): String {
        val seconds = ((millis + 999) / 1_000).coerceAtLeast(1)
        return if (seconds < 60) "$seconds s" else "${(seconds + 59) / 60} min"
    }

    object English : GuideTexts {
        override fun railways() = "Looking up railway lines…"
        override fun roads() = "Looking up the roads…"
        override fun routeTowards(name: String) = "Planning the route towards $name…"
        override fun searching(radius: String) = "Searching Wikipedia within $radius…"
        override fun buildings() = "Looking up buildings nearby…"
        override fun town() = "Looking up which town this is…"
        override fun translating(title: String) = "Translating $title…"
        override fun reading(title: String) = "Reading about $title…"
        override fun nextStoryIn(duration: String) = "Next story in $duration"
        override fun nothingYet(duration: String) = "Nothing nearby worth a story yet; looking further afield in $duration"
        override fun widerIn(radius: String, duration: String) = "Nothing nearby; looking further afield, within $radius. Wider in $duration"
        override fun widest(radius: String) = "Nothing nearby; looking further afield, within $radius"
    }
}
