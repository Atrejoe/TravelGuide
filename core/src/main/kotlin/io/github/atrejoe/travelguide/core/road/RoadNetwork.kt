package io.github.atrejoe.travelguide.core.road

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.nav.Maneuver
import io.github.atrejoe.travelguide.core.nav.ManeuverParser
import io.github.atrejoe.travelguide.core.rail.OsmWay

/**
 * Roads around [center] from OpenStreetMap, with their names and numbers: major roads within
 * [majorRadiusM], and minor ones (residential streets, cycleways…) within the smaller
 * [minorRadiusM], or none when that is 0.
 */
class RoadNetwork(
    val center: GeoPoint,
    val majorRadiusM: Double,
    val minorRadiusM: Double,
    val ways: List<OsmWay>,
    val fetchedAtMillis: Long,
    /** Whether cycleways were included. */
    val cycleways: Boolean = false,
) {
    val hasMinorRoads: Boolean get() = minorRadiusM > 0

    /** True when [p] lies well inside the area this network was fetched for. */
    fun covers(p: GeoPoint): Boolean =
        GeoMath.distance(center, p) < 0.7 * (if (hasMinorRoads) minorRadiusM else majorRadiusM)
}

/** Fetches the roads around a point. */
fun interface RoadSource {
    suspend fun roads(center: GeoPoint, majorRadiusM: Int, minorRadiusM: Int, cycleways: Boolean): RoadNetwork
}

/** The `highway` tag: "primary", "residential"… */
val OsmWay.highway: String? get() = tags["highway"]

/** The street name, if any. */
val OsmWay.name: String? get() = tags["name"]?.takeIf { it.isNotBlank() }

/** Road numbers such as "A2" or "N201"; a way can carry several ("A2;E35"). */
val OsmWay.refs: List<String> get() = splitList(tags["ref"])

val OsmWay.isRoundabout: Boolean get() = tags["junction"] == "roundabout" || tags["junction"] == "circular"

/** A slip road: motorway exits and the like. */
val OsmWay.isLink: Boolean get() = highway?.endsWith("_link") == true

/**
 * Which way traffic may use this way: 1 only along its node order, -1 only against it, 0 both.
 * Cyclists may ride against the flow where `oneway:bicycle=no`.
 */
fun OsmWay.oneway(bicycle: Boolean): Int {
    if (bicycle && tags["oneway:bicycle"] == "no") return 0
    return when (tags["oneway"]) {
        "yes", "true", "1" -> 1
        "-1", "reverse" -> -1
        "no", "false", "0" -> 0
        else -> if (isRoundabout || highway == "motorway" || highway == "motorway_link") 1 else 0
    }
}

/** True when two ways are the same road: same name or number, or both part of a roundabout. */
fun sameRoad(a: OsmWay, b: OsmWay): Boolean {
    if (a.id == b.id) return true
    if (a.isRoundabout && b.isRoundabout) return true
    val name = a.name
    if (name != null && name == b.name) return true
    val refs = a.refs
    return refs.isNotEmpty() && refs.any { it in b.refs }
}

/**
 * Whether a navigation instruction names this road, by its name, number or signposted
 * destinations. The text is compared as a whole, so this works in any language: "Turn left onto
 * Main Street", "Sla linksaf naar de Hoofdstraat" and "Auf die A 2 auffahren" all match without
 * understanding their grammar.
 */
fun mentions(maneuver: Maneuver, way: OsmWay): Boolean {
    val text = maneuver.text
    if (text.isBlank()) return false
    way.name?.let { if (containsWords(text, ManeuverParser.normalize(it))) return true }
    for (ref in way.refs + splitList(way.tags["destination:ref"])) {
        if (refPattern(ref)?.containsMatchIn(text) == true) return true
    }
    // Signposted destinations of slip roads ("Utrecht;Amersfoort"), matched against "towards …".
    for (destination in splitList(way.tags["destination"])) {
        if (containsWords(text, ManeuverParser.normalize(destination))) return true
    }
    return false
}

private fun splitList(value: String?): List<String> =
    value?.split(';')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()

/** True when [words] occurs in [text] as whole words; very short names are ignored. */
private fun containsWords(text: String, words: String): Boolean {
    if (words.length < 3) return false
    var index = text.indexOf(words)
    while (index >= 0) {
        val before = text.getOrNull(index - 1)
        val after = text.getOrNull(index + words.length)
        if ((before == null || !before.isLetterOrDigit()) && (after == null || !after.isLetterOrDigit())) return true
        index = text.indexOf(words, index + 1)
    }
    return false
}

/** "A2" and "A 2" both match "a2" and "a 2", but not "a20". */
private fun refPattern(ref: String): Regex? {
    val compact = ManeuverParser.normalize(ref).replace(" ", "")
    val parts = Regex("[a-z]+|\\d+").findAll(compact).map { it.value }.toList()
    if (parts.isEmpty() || parts.none { p -> p.any(Char::isDigit) }) return null
    return Regex("(?<![a-z0-9])" + parts.joinToString("\\s?") { Regex.escape(it) } + "(?![a-z0-9])")
}
