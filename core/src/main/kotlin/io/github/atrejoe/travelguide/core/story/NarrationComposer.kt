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
 * Builds the spoken story: an inviting opener that says where the place is, then the most
 * interesting bits of its article (see [StoryWriter]), and now and then a closing remark.
 * No language model involved: variety comes from phrasing pools and sentence selection.
 */
class NarrationComposer(private val random: Random = Random.Default) {
    private val writer = StoryWriter(random)
    private var lastOpener: String? = null

    /**
     * @param sections the full article when it could be fetched; otherwise the lead section from
     * the geosearch result is used.
     */
    fun compose(
        scored: ScoredPoi,
        motion: MotionState,
        profile: DetailProfile,
        sections: List<ArticleSection>? = null,
    ): Narration {
        val poi = scored.poi
        val relative = relativePosition(scored, motion)
        val material = sections?.takeIf { it.isNotEmpty() }
            ?: listOf(ArticleSection("", SectionKind.INTRO, poi.extract))
        val body = writer.write(poi, material, profile.maxSentences)
        val closing = closing(relative, motion.mode)
        return Narration(poi, listOf(opener(poi, relative, motion.mode)) + body + listOfNotNull(closing), relative)
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
            RelativePosition.Here -> when {
                poi.type.isArea && mode == TravelMode.FLYING ->
                    listOf("Below us now lies $t.", "We are flying over $t.")
                poi.type.isArea -> listOf(
                    "Welcome to $t.",
                    "We've just arrived in $t.",
                    "You are now travelling through $t.",
                    "And here we are, in $t.",
                )
                else -> listOf(
                    "Take a look around: you are right next to $t.",
                    "Right here is $t.",
                    "Stop for a moment. This is $t.",
                )
            }
            is RelativePosition.Ahead -> {
                val d = distancePhrase(relative.distanceM)
                listOf(
                    "Coming up ahead, about $d away, is $t.",
                    "Keep your eyes on the road ahead: in about $d we reach $t.",
                    "Just ahead, some $d from here, lies $t.",
                )
            }
            is RelativePosition.Side -> {
                val side = if (relative.left) "left" else "right"
                val d = distancePhrase(relative.distanceM)
                when (mode) {
                    TravelMode.TRAIN, TravelMode.FLYING -> listOf(
                        "Look out of the $side-hand window. About $d away lies $t.",
                        "If you have a $side-hand window seat, look out now: about $d away is $t.",
                    )
                    else -> listOf(
                        "Look to your $side. About $d away is $t.",
                        "Over on your $side, roughly $d away, lies $t.",
                        "Off to the $side, about $d from here, you'll find $t.",
                    )
                }
            }
            is RelativePosition.Behind -> {
                val d = distancePhrase(relative.distanceM)
                listOf("Just behind us, about $d away, is $t.", "We have just passed $t, about $d behind us.")
            }
            is RelativePosition.Compass -> {
                val d = distancePhrase(relative.distanceM)
                listOf(
                    "About $d to the ${relative.direction} lies $t.",
                    "Not far from here, $d to the ${relative.direction}, is $t.",
                )
            }
        }
        // Avoid starting two stories in a row with the same words.
        val fresh = options.filter { it != lastOpener }.ifEmpty { options }
        return fresh[random.nextInt(fresh.size)].also { lastOpener = it }
    }

    private fun closing(relative: RelativePosition, mode: TravelMode): String? {
        if (random.nextDouble() > 0.4) return null
        val options = when {
            relative is RelativePosition.Behind -> return null
            mode == TravelMode.WALKING || mode == TravelMode.STATIONARY ->
                listOf("Take a moment to look around.", "Worth a closer look, if you have the time.")
            mode == TravelMode.CYCLING -> listOf("Worth a stop, if your legs agree.", "Something to look out for as you ride.")
            relative is RelativePosition.Here -> listOf("Keep your eyes open as we pass through.")
            else -> listOf("Keep an eye out as we pass.", "Something to remember for a future visit.")
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
