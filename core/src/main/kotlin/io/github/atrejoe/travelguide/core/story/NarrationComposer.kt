package io.github.atrejoe.travelguide.core.story

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.lang.English
import io.github.atrejoe.travelguide.core.lang.StoryLanguage
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
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
    data class Compass(val bearingDeg: Double, val distanceM: Double) : RelativePosition {
        /** In English, for screens. */
        val direction: String get() = English.compassName(bearingDeg)
    }
}

/** A story ready to be spoken, split into sentences so playback can pause and resume cleanly. */
data class Narration(
    val poi: Poi,
    val sentences: List<String>,
    val relative: RelativePosition,
    /** Nearby places woven into this story. */
    val companions: List<Poi> = emptyList(),
    /**
     * How many sentences after the opening one tell about [poi] itself; the rest are about the
     * [companions] and a closing remark. A retold story (see StoryEnhancement) replaces just these.
     */
    val bodySize: Int = (sentences.size - 1).coerceAtLeast(0),
    /** The language the story is told in. */
    val lang: String = poi.lang,
    /** The language each sentence is in: a place's own sentences stay in its article's until translated (issue #71). */
    val sentenceLangs: List<String> = sentences.map { lang },
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
     * @param afield true for a place further away than we would normally tell about, which you
     * probably can't see (issue #64).
     * @param storyLanguage the language to tell in: openers, transitions and closings are in it,
     * while what the article says stays in the article's language, to be translated (issue #71).
     */
    fun compose(
        scored: ScoredPoi,
        motion: MotionState,
        profile: DetailProfile,
        sections: List<ArticleSection>? = null,
        companions: List<ScoredPoi> = emptyList(),
        relative: RelativePosition? = null,
        afield: Boolean = false,
        storyLanguage: StoryLanguage = StoryLanguages.of(scored.poi.lang),
    ): Narration {
        val poi = scored.poi
        val language = storyLanguage
        val relative = relative ?: relativePosition(scored, motion)
        val material = sections?.takeIf { it.isNotEmpty() }
            ?: listOf(ArticleSection("", SectionKind.INTRO, poi.extract))
        // Leave room for the places woven in after the main story.
        val budget = (profile.maxSentences - companions.size).coerceAtLeast(2)
        val fromFacts = sections.isNullOrEmpty() && poi.extract.isBlank() && poi.facts != null
        val body = if (fromFacts) placeWriter.write(poi, budget) else writer.write(poi, material, budget)

        val articleText = material.joinToString(" ") { it.text }
        val extraLangs = mutableListOf<String>()
        val extra = companions.flatMap { companion ->
            val mentioned = mentions(articleText, companion.poi.title)
            val count = if (profile.maxSentences >= 4) 2 else 1
            val about = if (companion.poi.extract.isBlank() && companion.poi.facts != null) {
                placeWriter.write(companion.poi, count)
            } else {
                writer.companion(companion.poi, count)
            }
            extraLangs += language.code
            extraLangs += about.map { companion.poi.lang }
            listOf(transition(companion, relativePosition(companion, motion), mentioned, language)) + about
        }
        val closing = if (afield) null else closing(relative, motion.mode, language)
        val opener = pickOpener(if (afield) language.afieldOpeners(poi.title, relative) else openers(poi, relative, motion.mode, language))
        return Narration(
            poi,
            listOf(opener) + body + extra + listOfNotNull(closing),
            relative,
            companions.map { it.poi },
            bodySize = body.size,
            lang = language.code,
            sentenceLangs = listOf(language.code) + body.map { poi.lang } + extraLangs + listOfNotNull(closing?.let { language.code }),
        )
    }

    /** Leads from the main story into a nearby place, preferably via a real connection. */
    private fun transition(companion: ScoredPoi, relative: RelativePosition, mentioned: Boolean, language: StoryLanguage): String {
        val options = language.transitions(companion.poi.title, language.where(relative), mentioned)
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
            return RelativePosition.Compass(bearing, distance)
        }
        val angle = GeoMath.angleDiff(heading, bearing)
        return when {
            abs(angle) <= 30 -> RelativePosition.Ahead(distance)
            abs(angle) >= 150 -> RelativePosition.Behind(distance)
            else -> RelativePosition.Side(left = angle < 0, distance)
        }
    }

    private fun openers(poi: Poi, relative: RelativePosition, mode: TravelMode, language: StoryLanguage): List<String> {
        val t = poi.title
        return when (relative) {
            RelativePosition.Here -> language.hereOpeners(t, poi.type.isArea, mode == TravelMode.FLYING)
            is RelativePosition.Ahead -> language.aheadOpeners(t, language.distance(relative.distanceM))
            is RelativePosition.Side -> language.sideOpeners(
                t,
                relative.left,
                language.distance(relative.distanceM),
                window = mode == TravelMode.TRAIN || mode == TravelMode.FLYING,
            )
            is RelativePosition.Behind -> language.behindOpeners(t, language.distance(relative.distanceM))
            is RelativePosition.Compass ->
                language.compassOpeners(t, language.compassName(relative.bearingDeg), language.distance(relative.distanceM))
        }
    }

    /** One of [options], avoiding starting two stories in a row with the same words. */
    private fun pickOpener(options: List<String>): String {
        val fresh = options.filter { it != lastOpener }.ifEmpty { options }
        return fresh[random.nextInt(fresh.size)].also { lastOpener = it }
    }

    private fun closing(relative: RelativePosition, mode: TravelMode, language: StoryLanguage): String? {
        if (random.nextDouble() > 0.4) return null
        val options = language.closings(relative, mode).ifEmpty { return null }
        return options[random.nextInt(options.size)]
    }

    companion object {
        /** True when [text] names [title] as a whole word. */
        fun mentions(text: String, title: String): Boolean {
            val name = title.substringBefore(" (").substringBefore(",").trim()
            if (name.length < 4) return false
            return Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

        /** Spoken location in English, e.g. "on your left, about 800 metres away". */
        fun where(relative: RelativePosition): String = English.where(relative)

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

        fun distancePhrase(meters: Double): String = English.distance(meters)

        fun compassName(bearing: Double): String = English.compassName(bearing)
    }
}
