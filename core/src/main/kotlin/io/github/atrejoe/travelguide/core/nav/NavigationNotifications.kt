package io.github.atrejoe.travelguide.core.nav

/** A navigation app whose turn-by-turn notification we recognise. */
data class NavigationApp(val name: String, val origin: RouteOrigin)

/**
 * What a navigation app's notification tells us: who is navigating, the next instruction as shown
 * and, as far as it can be read, the next [maneuver].
 */
data class NavigationStatus(
    val app: NavigationApp,
    val instruction: String?,
    val maneuver: Maneuver? = null,
    /** When the notification was last posted or updated. */
    val receivedAtMillis: Long = 0,
) {
    /** "Google Maps: Turn left onto Main Street · 200 m". */
    fun label(): String = app.name + (instruction?.let { ": $it" } ?: ": navigating")
}

/**
 * Recognises turn-by-turn notifications from known navigation apps. Notifications don't contain
 * the route itself, only the next instruction, which is read into a [Maneuver] so the forecast can
 * follow the road through the next turn. Also used to show who is navigating and to notice when
 * navigation ends.
 */
object NavigationNotifications {

    val apps: Map<String, NavigationApp> = buildMap {
        put("com.google.android.apps.maps", NavigationApp("Google Maps", RouteOrigin.GOOGLE_MAPS))
        put("de.komoot.android", NavigationApp("Komoot", RouteOrigin.KOMOOT))
        val garmin = NavigationApp("Garmin", RouteOrigin.GARMIN)
        put("com.garmin.android.apps.connectmobile", garmin)
        put("com.garmin.android.apps.explore", garmin)
        put("com.garmin.android.apps.drive", garmin)
        put("com.garmin.android.apps.messenger", garmin)
        val osmand = NavigationApp("OsmAnd", RouteOrigin.OSMAND)
        put("net.osmand", osmand)
        put("net.osmand.plus", osmand)
        put("com.waze", NavigationApp("Waze", RouteOrigin.WAZE))
    }

    private const val CATEGORY_NAVIGATION = "navigation"
    private const val MAX_LENGTH = 140

    /**
     * Returns the navigation status for a notification, or null when it is not a navigation
     * notification from a known app. Only ongoing notifications (or ones in the "navigation"
     * category) count, so a "your trip summary" message doesn't look like active navigation.
     */
    fun parse(
        packageName: String,
        category: String?,
        ongoing: Boolean,
        title: CharSequence?,
        text: CharSequence?,
        subText: CharSequence? = null,
        receivedAtMillis: Long = 0,
    ): NavigationStatus? {
        val app = apps[packageName] ?: return null
        if (!ongoing && category != CATEGORY_NAVIGATION) return null
        return NavigationStatus(app, instruction(title, text, subText), ManeuverParser.parse(title, text, subText), receivedAtMillis)
    }

    /** Joins the title and text into one short line, e.g. "Turn left onto Main St · 200 m". */
    fun instruction(title: CharSequence?, text: CharSequence?, subText: CharSequence? = null): String? {
        val parts = listOf(title, text, subText)
            .mapNotNull { it?.toString()?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { s -> s.isNotEmpty() } }
            .distinct()
        if (parts.isEmpty()) return null
        val joined = parts.joinToString(" · ")
        return if (joined.length > MAX_LENGTH) joined.take(MAX_LENGTH - 1).trimEnd() + "…" else joined
    }
}
