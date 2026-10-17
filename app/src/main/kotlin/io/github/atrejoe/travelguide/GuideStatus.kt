package io.github.atrejoe.travelguide

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.route.PathSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StoryItem(
    val title: String,
    val text: String,
    val url: String,
    val timeMillis: Long,
    /** Nearby places woven into the story. */
    val also: List<String> = emptyList(),
    /** The places the story is about, for the map: the main place first. */
    val places: List<MapPlace> = emptyList(),
)

data class MapPlace(val title: String, val point: GeoPoint)

/** A story we expect to tell, shown on the timeline. */
data class UpcomingItem(
    val title: String,
    /** Where it is, e.g. "left · 800 m". */
    val where: String,
    /** When we expect to pass it, or null when not moving. */
    val expectedAtMillis: Long?,
    val point: GeoPoint,
)

data class GuideUiState(
    val running: Boolean = false,
    val paused: Boolean = false,
    /** Why we are paused when it was not the user's choice, e.g. a phone call. */
    val pauseReason: String? = null,
    val speaking: Boolean = false,
    val mode: TravelMode? = null,
    val speedKmh: Double? = null,
    val knownPlaces: Int = 0,
    val current: StoryItem? = null,
    val history: List<StoryItem> = emptyList(),
    val upcoming: List<UpcomingItem> = emptyList(),
    val position: GeoPoint? = null,
    val headingDeg: Double? = null,
    /** The stretch we expect to travel next, and whether it is planned or guessed. */
    val route: List<GeoPoint> = emptyList(),
    val routeSource: PathSource = PathSource.NONE,
    val message: String? = null,
    /** The voice actually speaking, and why it differs from the chosen one, if it does. */
    val voiceLabel: String? = null,
    val voiceWarning: String? = null,
)

/** Process-wide guide state, written by [service.GuideService] and observed by UI and tile. */
object GuideStatus {
    private val mutable = MutableStateFlow(GuideUiState())
    val state: StateFlow<GuideUiState> = mutable.asStateFlow()

    fun update(transform: (GuideUiState) -> GuideUiState) = mutable.update(transform)
}
