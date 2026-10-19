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
    /** Nearby places woven into this story. */
    val companions: List<Poi> = emptyList(),
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

    private val placeWriter = PlaceStoryWriter(random)

    /**
     * @param sections the full article when it could be fetched; otherwise the lead section from
     * the geosearch result is used, or for a place without an article, its facts.
     * @param relative where the place is, when known better than from its position (e.g. the town
     * we are walking in).
     */
    fun compose(
        scored: ScoredPoi,
        motion: MotionState,
        profile: DetailProfile,
        sections: List<ArticleSection>? = null,
        companions: List<ScoredPoi> = emptyList(),
        relative: RelativePosition? = null,
    ): Narration {
        val poi = scored.poi
        val relative = relative ?: relativePosition(scored, motion)
        val material = sections?.takeIf { it.isNotEmpty() }
            ?: listOf(ArticleSection("", SectionKind.INTRO, poi.extract))
        // Leave room for the places woven in after the main story.
        val budget = (profile.maxSentences - companions.size).coerceAtLeast(2)
        val fromFacts = sections.isNullOrEmpty() && poi.extract.isBlank() && poi.facts != null
        val body = if (fromFacts) placeWriter.write(poi, budget) else writer.write(poi, material, budget)

        val articleText = material.joinToString(" ") { it.text }
        val extra = companions.flatMap { companion ->
            val mentioned = mentions(articleText, companion.poi.title)
            val count = if (profile.maxSentences >= 4) 2 else 1
            val about = if (companion.poi.extract.isBlank() && companion.poi.facts != null) {
                placeWriter.write(companion.poi, count)
            } else {
                writer.companion(companion.poi, count)
            }
            listOf(transition(companion, relativePosition(companion, motion), mentioned)) + about
        }
        val closing = closing(relative, motion.mode)
        return Narration(
            poi,
            listOf(opener(poi, relative, motion.mode)) + body + extra + listOfNotNull(closing),
            relative,
            companions.map { it.poi },
        )
    }

    /** Leads from the main story into a nearby place, preferably via a real connection. */
    private fun transition(companion: ScoredPoi, relative: RelativePosition, mentioned: Boolean): String {
        val t = companion.poi.title
        val where = where(relative)
        val options = if (mentioned) {
            listOf("Which brings us to $t, $where.", "And that story continues at $t, $where.")
        } else {
            listOf(
                "A little further along, $where, you'll find $t.",
                "Not far from there, $where, lies $t.",
                "And while we're here: $where is $t.",
            )
        }
        return options[random.nextInt(options.size)]
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
        /** True when [text] names [title] as a whole word. */
        fun mentions(text: String, title: String): Boolean {
            val name = title.substringBefore(" (").substringBefore(",").trim()
            if (name.length < 4) return false
            return Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

        /** Spoken location, e.g. "on your left, about 800 metres away". */
        fun where(relative: RelativePosition): String = when (relative) {
            RelativePosition.Here -> "right here"
            is RelativePosition.Ahead -> "straight ahead, about ${distancePhrase(relative.distanceM)} away"
            is RelativePosition.Side ->
                "on your ${if (relative.left) "left" else "right"}, about ${distancePhrase(relative.distanceM)} away"
            is RelativePosition.Behind -> "just behind us"
            is RelativePosition.Compass -> "${distancePhrase(relative.distanceM)} to the ${relative.direction}"
        }

        /** Short label for screens, e.g. "left · 800 m". */
        fun label(relative: RelativePosition): String {
            fun short(m: Double) = if (m < 1_000) "${((m / 100).roundToInt() * 100).coerceAtLeast(100)} m" else "${"%.1f".format(java.util.Locale.ROOT, m / 1000)} km"
            return when (relative) {
                RelativePosition.Here -> "here"
                is RelativePosition.Ahead -> "ahead · ${short(relative.distanceM)}"
                is RelativePosition.Side -> "${if (relative.left) "left" else "right"} · ${short(relative.distanceM)}"
                is RelativePosition.Behind -> "behind · ${short(relative.distanceM)}"
                is RelativePosition.Compass -> "${relative.direction} · ${short(relative.distanceM)}"
            }
        }

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
