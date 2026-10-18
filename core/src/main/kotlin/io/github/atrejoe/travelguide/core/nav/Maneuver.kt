package io.github.atrejoe.travelguide.core.nav

import java.text.Normalizer
import java.util.Locale

/** Which way the next maneuver goes, as far as the instruction says. */
enum class TurnDirection(
    /** Typical angle relative to the direction of travel, degrees; negative is to the left. */
    val nominalDeg: Double?,
    /** Branches whose relative angle lies in this range fit the instruction; null when any may. */
    val window: ClosedFloatingPointRange<Double>?,
) {
    STRAIGHT(0.0, -30.0..30.0),
    SLIGHT_LEFT(-35.0, -75.0..-8.0),
    LEFT(-90.0, -150.0..-40.0),
    SHARP_LEFT(-135.0, -175.0..-95.0),
    SLIGHT_RIGHT(35.0, 8.0..75.0),
    RIGHT(90.0, 40.0..150.0),
    SHARP_RIGHT(135.0, 95.0..175.0),
    KEEP_LEFT(-10.0, -60.0..10.0),
    KEEP_RIGHT(10.0, -10.0..60.0),

    /** Turning around: the road ahead can't be followed. */
    U_TURN(null, null),

    /** Entering a roundabout; which exit is in [Maneuver.roundaboutExit] when known. */
    ROUNDABOUT(null, null),

    /** Leaving a motorway or main road by an exit (slip road). */
    EXIT(null, -75.0..75.0),
}

/**
 * The next maneuver of a navigation app, read from its notification, e.g. "In 300 m turn left onto
 * Main Street towards Utrecht".
 */
data class Maneuver(
    /** Distance from where the notification was posted to the maneuver, in metres. */
    val distanceM: Double?,
    val turn: TurnDirection?,
    /** For [TurnDirection.ROUNDABOUT]: which exit to take, counting from 1. */
    val roundaboutExit: Int? = null,
    /** A "towards X" place, as written on the signs. */
    val toward: String? = null,
    /** The instruction, normalised for matching against road names (see [ManeuverParser.normalize]). */
    val text: String = "",
) {
    /** True when there is anything to steer the forecast with. */
    val isUseful: Boolean get() = turn != null || distanceM != null || toward != null || text.isNotBlank()
}

/**
 * Reads [Maneuver]s from the free text of navigation notifications in English, Dutch and German.
 * Road names are not read from the grammar: the whole text is kept so it can be matched against
 * the names and numbers of the roads nearby, which works in any language.
 */
object ManeuverParser {

    /** Parses one instruction, e.g. "300 m · Sla linksaf naar Hoofdstraat". */
    fun parse(text: String?): Maneuver? {
        if (text.isNullOrBlank()) return null
        val normalized = normalize(text)
        val turn = turn(normalized)
        val maneuver = Maneuver(
            distanceM = distance(text),
            turn = turn,
            roundaboutExit = if (turn == TurnDirection.ROUNDABOUT) roundaboutExit(normalized) else null,
            toward = toward(text),
            text = normalized,
        )
        return maneuver.takeIf { it.isUseful }
    }

    /**
     * Parses the title and text of a notification. The sub-text usually holds the trip summary
     * ("15 min · 12 km · 10:45"), whose distance is not the one to the next maneuver, so it is
     * only used when the others are empty.
     */
    fun parse(title: CharSequence?, text: CharSequence?, subText: CharSequence? = null): Maneuver? {
        val main = listOfNotNull(title, text).joinToString(" · ") { it.toString() }.trim()
        return parse(main.ifBlank { subText?.toString() })
    }

    /** Lower case, without accents and with single spaces, for matching. */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase(Locale.ROOT)
            .replace("ß", "ss")
            .replace(Regex("\\s+"), " ")
            .trim()

    private val distancePattern = Regex(
        "(?<![\\d.,])(\\d+(?:[.,]\\d+)?)\\s?(km|kilometers?|kilometres?|kilometer|m|meters?|metres?|meter|metern|mi|miles?|ft|feet|yd|yards?)(?![\\p{L}\\d])",
        RegexOption.IGNORE_CASE,
    )

    /** The first distance in [text], in metres, e.g. "1,2 km" → 1200, "500 ft" → 152. */
    fun distance(text: String): Double? {
        val match = distancePattern.find(text) ?: return null
        val (number, unit) = match.destructured
        val factor = when (unit.lowercase(Locale.ROOT)) {
            "km", "kilometer", "kilometers", "kilometre", "kilometres" -> 1_000.0
            "m", "meter", "meters", "metre", "metres", "metern" -> 1.0
            "mi", "mile", "miles" -> 1_609.344
            "ft", "feet" -> 0.3048
            "yd", "yard", "yards" -> 0.9144
            else -> return null
        }
        // "1,200 ft" uses a thousands separator; "1,2 km" a decimal comma.
        val thousands = Regex("\\d+,\\d{3}").matches(number) && factor < 1_000.0
        val value = (if (thousands) number.replace(",", "") else number.replace(',', '.')).toDoubleOrNull() ?: return null
        return value * factor
    }

    private val uTurn = Regex("\\b(u-turn|u turn|keer om|omkeren|keren|wenden|kehrtwende|umkehren)\\b")
    private val roundabout = Regex("\\b(roundabout|rotary|traffic circle|rotonde|verkeersplein|kreisverkehr|kreisel)")

    /** Turn phrases; the one that starts first in the text wins, the longer one on a tie. */
    private val turns: List<Pair<Regex, TurnDirection>> = listOf(
        "sharp left|sharply left|scherp(e bocht naar)? links(af)?|scharf links" to TurnDirection.SHARP_LEFT,
        "sharp right|sharply right|scherp(e bocht naar)? rechts(af)?|scharf rechts" to TurnDirection.SHARP_RIGHT,
        "slight left|slightly left|bear left|flauwe bocht (naar )?links|licht links(af)?|schuin links(af)?|leicht links" to TurnDirection.SLIGHT_LEFT,
        "slight right|slightly right|bear right|flauwe bocht (naar )?rechts|licht rechts(af)?|schuin rechts(af)?|leicht rechts" to TurnDirection.SLIGHT_RIGHT,
        "keep left|stay left|keep to the left|houd links( aan)?|links aanhouden|links houden|links halten|links bleiben|links einordnen" to TurnDirection.KEEP_LEFT,
        "keep right|stay right|keep to the right|houd rechts( aan)?|rechts aanhouden|rechts houden|rechts halten|rechts bleiben|rechts einordnen" to TurnDirection.KEEP_RIGHT,
        "take (the )?exit|exit|afrit|afslag|ausfahrt" to TurnDirection.EXIT,
        "left|linksaf|links|linksabbiegen|links abbiegen" to TurnDirection.LEFT,
        "right|rechtsaf|rechts|rechtsabbiegen|rechts abbiegen" to TurnDirection.RIGHT,
        "continue|straight|go straight|head|rechtdoor|ga verder|blijf op|volg|geradeaus|weiterfahren|weiter auf|folgen" to TurnDirection.STRAIGHT,
    ).map { (words, turn) -> Regex("\\b($words)\\b") to turn }

    /** The turn in normalised text, see [normalize]. */
    fun turn(normalized: String): TurnDirection? {
        if (uTurn.containsMatchIn(normalized)) return TurnDirection.U_TURN
        if (roundabout.containsMatchIn(normalized)) return TurnDirection.ROUNDABOUT
        var best: Pair<MatchResult, TurnDirection>? = null
        for ((pattern, turn) in turns) {
            val match = pattern.find(normalized) ?: continue
            val current = best?.first
            val better = current == null ||
                match.range.first < current.range.first ||
                (match.range.first == current.range.first && match.value.length > current.value.length)
            if (better) best = match to turn
        }
        return best?.second
    }

    private val ordinals = mapOf(
        "first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5,
        "eerste" to 1, "tweede" to 2, "derde" to 3, "vierde" to 4, "vijfde" to 5,
        "erste" to 1, "zweite" to 2, "dritte" to 3, "vierte" to 4, "funfte" to 5,
    )
    private val numberedExit = Regex("\\b(\\d)(?:st|nd|rd|th|e|ste|de|\\.)?\\s+(?:exit|afslag|afrit|uitgang|ausfahrt)")
    private val wordExit = Regex("\\b(${ordinals.keys.joinToString("|")})\\s+(?:exit|afslag|afrit|uitgang|ausfahrt)")

    /** "Take the 2nd exit", "neem de tweede afslag", "nehmen Sie die 2. Ausfahrt" → 2. */
    fun roundaboutExit(normalized: String): Int? {
        numberedExit.find(normalized)?.let { return it.groupValues[1].toInt().takeIf { n -> n in 1..8 } }
        return wordExit.find(normalized)?.let { ordinals[it.groupValues[1]] }
    }

    private val towardPattern = Regex(
        "\\b(?:towards?|toward|in de richting van|richting|in richtung|richtung)\\s*:?\\s+(.+)",
        RegexOption.IGNORE_CASE,
    )
    private val towardEnd = Regex("\\s*(?:[·•|;/()]|,|\\s-\\s|\\s–\\s|\\bvia\\b|\\b(?:onto|auf|naar|nach)\\s|\\d+\\s?(?:m|km|mi|ft)\\b).*$", RegexOption.IGNORE_CASE)

    /** The place in "towards Utrecht", "richting Amersfoort", "Richtung Köln". */
    fun toward(text: String): String? {
        val match = towardPattern.find(text) ?: return null
        val place = match.groupValues[1].replace(towardEnd, "").trim().trimEnd('.', '!', ':')
        return place.takeIf { it.length in 2..60 && it.any(Char::isLetter) }
    }
}
