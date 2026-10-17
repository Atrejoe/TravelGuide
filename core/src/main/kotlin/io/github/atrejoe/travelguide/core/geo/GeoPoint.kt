package io.github.atrejoe.travelguide.core.geo

/** A WGS84 coordinate in decimal degrees. */
data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat in -90.0..90.0) { "Latitude out of range: $lat" }
        require(lon in -180.0..180.0) { "Longitude out of range: $lon" }
    }
}

/** A single position report from the device. */
data class LocationFix(
    val point: GeoPoint,
    val timeMillis: Long,
    /** Ground speed in metres per second, if the provider reported one. */
    val speedMps: Double? = null,
    /** Direction of travel in degrees clockwise from north, if reported. */
    val bearingDeg: Double? = null,
    /** Horizontal accuracy radius in metres, if reported. */
    val accuracyM: Double? = null,
)
