package io.github.atrejoe.travelguide

import io.github.atrejoe.travelguide.core.motion.TravelMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StoryItem(
    val title: String,
    val text: String,
    val url: String,
    val timeMillis: Long,
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
    val message: String? = null,
)

/** Process-wide guide state, written by [service.GuideService] and observed by UI and tile. */
object GuideStatus {
    private val mutable = MutableStateFlow(GuideUiState())
    val state: StateFlow<GuideUiState> = mutable.asStateFlow()

    fun update(transform: (GuideUiState) -> GuideUiState) = mutable.update(transform)
}
