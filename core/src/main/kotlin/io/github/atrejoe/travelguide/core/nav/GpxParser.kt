package io.github.atrejoe.travelguide.core.nav

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.helpers.DefaultHandler
import java.io.InputStream
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory

/** What a GPX file holds, before simplification. */
data class GpxRoute(
    val name: String?,
    /** The `creator` attribute, e.g. "komoot" or "Garmin Connect", used to tell where it came from. */
    val creator: String?,
    val points: List<GeoPoint>,
)

/**
 * Streams a GPX 1.0/1.1 file with SAX, so large recordings don't need to fit in memory as a DOM.
 *
 * Takes the track (`trk/trkseg/trkpt`) when there is one, otherwise the route (`rte/rtept`), and
 * as a last resort the waypoints (`wpt`) in file order. Namespaces and prefixes are ignored, so
 * files with or without the GPX namespace both work.
 */
object GpxParser {

    /** Throws [RouteImportException] when the file is not valid XML. */
    fun parse(input: InputStream): GpxRoute {
        val handler = Handler()
        try {
            val factory = SAXParserFactory.newInstance().apply {
                isNamespaceAware = true
                isValidating = false
            }
            // No external entities or DTDs: a shared file must not make us read local files.
            for ((feature, value) in SAFE_FEATURES) {
                try {
                    factory.setFeature(feature, value)
                } catch (_: Exception) {
                    // Not every parser (e.g. Android's) knows every feature.
                }
            }
            factory.newSAXParser().parse(InputSource(input), handler)
        } catch (e: SAXException) {
            throw RouteImportException(RouteImportException.Reason.UNSUPPORTED, "This file is not a valid GPX file.", e)
        } catch (e: ParserConfigurationException) {
            throw RouteImportException(RouteImportException.Reason.UNSUPPORTED, "GPX files can't be read on this device.", e)
        }
        val points = when {
            handler.track.size >= 2 -> handler.track
            handler.route.size >= 2 -> handler.route
            else -> handler.waypoints
        }
        val name = handler.trackName ?: handler.routeName ?: handler.metadataName
        return GpxRoute(name?.trim()?.takeIf { it.isNotEmpty() }, handler.creator, points)
    }

    private val SAFE_FEATURES = listOf(
        "http://apache.org/xml/features/disallow-doctype-decl" to true,
        "http://xml.org/sax/features/external-general-entities" to false,
        "http://xml.org/sax/features/external-parameter-entities" to false,
        "http://apache.org/xml/features/nonvalidating/load-external-dtd" to false,
    )

    private class Handler : DefaultHandler() {
        val track = ArrayList<GeoPoint>()
        val route = ArrayList<GeoPoint>()
        val waypoints = ArrayList<GeoPoint>()
        var creator: String? = null
        var trackName: String? = null
        var routeName: String? = null
        var metadataName: String? = null

        private val path = ArrayList<String>()
        private var text: StringBuilder? = null

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val name = elementName(localName, qName)
            val parent = path.lastOrNull()
            path += name
            when (name) {
                "gpx" -> creator = attributes.value("creator")
                "trkpt" -> point(attributes)?.let(track::add)
                "rtept" -> point(attributes)?.let(route::add)
                "wpt" -> point(attributes)?.let(waypoints::add)
                "name" -> if (parent == "trk" || parent == "rte" || parent == "metadata") text = StringBuilder()
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            text?.appendRange(ch, start, start + length)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = elementName(localName, qName)
            if (name == "name") {
                val value = text?.toString()
                when (path.getOrNull(path.size - 2)) {
                    "trk" -> if (trackName == null) trackName = value
                    "rte" -> if (routeName == null) routeName = value
                    "metadata" -> if (metadataName == null) metadataName = value
                }
                text = null
            }
            if (path.isNotEmpty()) path.removeAt(path.lastIndex)
        }

        private fun elementName(localName: String?, qName: String?): String =
            localName?.takeIf { it.isNotEmpty() } ?: qName.orEmpty().substringAfter(':')

        private fun Attributes.value(name: String): String? =
            (0 until length).firstOrNull { getLocalName(it).ifEmpty { getQName(it) } == name }?.let { getValue(it) }

        private fun point(attributes: Attributes): GeoPoint? {
            val lat = attributes.value("lat")?.trim()?.toDoubleOrNull() ?: return null
            val lon = attributes.value("lon")?.trim()?.toDoubleOrNull() ?: return null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            return GeoPoint(lat, lon)
        }
    }
}
