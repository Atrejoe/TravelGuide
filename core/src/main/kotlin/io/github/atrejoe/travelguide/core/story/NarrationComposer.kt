package io.github.atrejoe.travelguide.core.story

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.motion.MotionState
import io.github.atrejoe.travelguide.core.motion.TravelMode
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

/** Where a place is, as the listener experiences it. */
sealed interface RelativePosition {
    data object Here : RelativePosition
    data class Ahead(val distanceM: Double) : RelativePosition
    data class Side(val left: Boolean, val distanceM: Double) : RelativePosition
    data class Behind(val distanceM: Double) : RelativePosition
    /** Used when we do not know the direction of travel. */
    data class Compass(val direction: String, val distanceM: Double) : RelativePosition
}

/** A story ready to be spoken, split into sentences so playback can pause and resume cleanly. */
data class Narration(
    val poi: Poi,
    val sentences: List<String>,
    val relative: RelativePosition,
) {
    val text: String get() = sentences.joinToString(" ")
}

/**
 * Builds the spoken text from templates and the article lead. No language model involved: the
 * variety comes from a handful of phrasings and the text itself from Wikipedia.
 */
class NarrationComposer(private val random: Random = Random.Default) {

    fun compose(scored: ScoredPoi, motion: MotionState, profile: DetailProfile): Narration {
        val poi = scored.poi
        val relative = relativePosition(scored, motion)
        val opener = opener(poi, relative, motion.mode)
        val body = SentenceSplitter.split(TextCleaner.clean(poi.extract))
            .filter { it.length > 2 }
            .take(profile.maxSentences)
        return Narration(poi, listOf(opener) + body, relative)
    }

    fun relativePosition(scored: ScoredPoi, motion: MotionState): RelativePosition {
        val poi = scored.poi
        val distance = scored.distanceM
        val hereRadius = if (poi.type.isArea) PoiScorer.insideRadius(poi) else 150.0
        if (distance <= hereRadius) return RelativePosition.Here

        val heading = motion.headingDeg
        val bearing = GeoMath.bearing(motion.fix.point, poi.point)
        if (heading == null || motion.mode == TravelMode.STATIONARY) {
            return RelativePosition.Compass(compassName(bearing), distance)
        }
        val angle = GeoMath.angleDiff(heading, bearing)
        return when {
            abs(angle) <= 30 -> RelativePosition.Ahead(distance)
            abs(angle) >= 150 -> RelativePosition.Behind(distance)
            else -> RelativePosition.Side(left = angle < 0, distance)
        }
    }

    private fun opener(poi: Poi, relative: RelativePosition, mode: TravelMode): String {
        val t = poi.title
        val options: List<String> = when (relative) {
            RelativePosition.Here -> if (poi.type.isArea) {
                if (mode == TravelMode.FLYING) {
                    listOf("Below us now lies $t.", "We are flying over $t.")
                } else {
                    listOf("You are now in $t.", "Welcome to $t.", "We are passing through $t.")
                }
            } else {
                listOf("You are right by $t.", "Right here is $t.", "Take a look around: this is $t.")
            }
            is RelativePosition.Ahead -> {
                val d = distancePhrase(relative.distanceM)
                listOf("Coming up ahead, $d away, is $t.", "Up ahead, about $d from here, lies $t.")
            }
            is RelativePosition.Side -> {
                val side = if (relative.left) "left" else "right"
                val d = distancePhrase(relative.distanceM)
                when (mode) {
                    TravelMode.TRAIN, TravelMode.FLYING ->
                        listOf("Look out of the $side-hand window: about $d away lies $t.")
                    else -> listOf("On your $side, about $d away, is $t.", "To your $side, some $d off, lies $t.")
                }
            }
            is RelativePosition.Behind -> {
                val d = distancePhrase(relative.distanceM)
                listOf("Just behind us, about $d away, is $t.")
            }
            is RelativePosition.Compass -> {
                val d = distancePhrase(relative.distanceM)
                listOf("About $d to the ${relative.direction} is $t.", "Nearby, $d to the ${relative.direction}, lies $t.")
            }
        }
        return options[random.nextInt(options.size)]
    }

    companion object {
        fun distancePhrase(meters: Double): String = when {
            meters < 950 -> "${((meters / 100).roundToInt() * 100).coerceAtLeast(100)} metres"
            meters < 1_050 -> "a kilometre"
            meters < 9_950 -> {
                val km = (meters / 100).roundToInt() / 10.0
                val text = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString()
                "$text kilometres"
            }
            else -> "${(meters / 1000).roundToInt()} kilometres"
        }

        fun compassName(bearing: Double): String {
            val names = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
            return names[((GeoMath.normalizeBearing(bearing) + 22.5) / 45).toInt() % 8]
        }
    }
}
