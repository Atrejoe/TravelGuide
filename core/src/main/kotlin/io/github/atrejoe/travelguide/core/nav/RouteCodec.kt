package io.github.atrejoe.travelguide.core.nav

/**
 * Stores an [ImportedRoute] as a few lines of text, so the app can keep it across restarts
 * without a database. The points are stored as a precision-6 polyline.
 */
object RouteCodec {
    private const val VERSION = "travelguide-route 1"

    fun encode(route: ImportedRoute): String = buildString {
        appendLine(VERSION)
        appendLine("origin=" + route.origin.name)
        route.name?.let { appendLine("name=" + oneLine(it)) }
        route.note?.let { appendLine("note=" + oneLine(it)) }
        appendLine("points=" + PolylineDecoder.encode(route.points, precision = 6))
    }

    /** Returns null for anything that isn't a stored route. */
    fun decode(text: String): ImportedRoute? {
        val lines = text.lines()
        if (lines.firstOrNull()?.trim() != VERSION) return null
        val values = lines.drop(1).filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        val origin = RouteOrigin.entries.firstOrNull { it.name == values["origin"] } ?: return null
        val points = PolylineDecoder.decode(values["points"] ?: return null, precision = 6)
        if (points.size < 2) return null
        return ImportedRoute(values["name"], origin, points, values["note"])
    }

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ')
}
