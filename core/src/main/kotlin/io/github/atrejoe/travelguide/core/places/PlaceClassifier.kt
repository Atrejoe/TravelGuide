package io.github.atrejoe.travelguide.core.places

import io.github.atrejoe.travelguide.core.story.PlaceKind

/**
 * Decides from OpenStreetMap tags what kind of place something is, and whether it is worth a
 * story at all: shops, cafés, offices and the like only count when they are historic.
 */
object PlaceClassifier {
    private val boringAmenities = setOf(
        "cafe", "restaurant", "bar", "pub", "fast_food", "ice_cream", "bank", "atm", "pharmacy",
        "parking", "parking_entrance", "bicycle_parking", "fuel", "car_wash", "post_office", "doctors",
        "dentist", "clinic", "kindergarten", "school", "toilets", "nightclub", "casino", "cinema",
        "marketplace", "car_rental", "bicycle_rental", "taxi", "bus_station", "social_facility",
    )

    fun kind(tags: Map<String, String>): PlaceKind? {
        if (tags["name"].isNullOrBlank()) return null
        val historic = tags["historic"]
        historicKind(historic, tags)?.let { return it }
        if (historic == "memorial" || historic == "wayside_cross" || historic == "wayside_shrine" ||
            historic == "boundary_stone" || historic == "milestone"
        ) {
            return null
        }
        val heritage = !tags["heritage"].isNullOrBlank() || !tags["ref:rce"].isNullOrBlank() || !tags["listed_status"].isNullOrBlank()
        val boring = tags["amenity"] in boringAmenities || tags.containsKey("shop") || tags.containsKey("office") ||
            tags.containsKey("craft") || tags["tourism"] in setOf("hotel", "hostel", "guest_house", "apartment")
        if (boring) return if (heritage || historic != null) PlaceKind.HISTORIC_BUILDING else null

        amenityKind(tags)?.let { return it }
        when (tags["tourism"]) {
            "museum" -> return PlaceKind.MUSEUM
            "gallery" -> return PlaceKind.GALLERY
            "artwork" -> return if (tags["artwork_type"] in setOf("statue", "bust", "sculpture")) PlaceKind.STATUE else PlaceKind.ARTWORK
            "viewpoint" -> return PlaceKind.VIEWPOINT
        }
        when (tags["man_made"]) {
            "windmill" -> return PlaceKind.WINDMILL
            "watermill" -> return PlaceKind.WATERMILL
            "lighthouse" -> return PlaceKind.LIGHTHOUSE
            "tower" -> if (heritage || tags["tower:type"] in setOf("bell_tower", "defensive", "observation", "clock")) return PlaceKind.TOWER
        }
        if (tags["railway"] == "station") return PlaceKind.STATION
        buildingKind(tags["building"])?.let { return it }
        if (tags["tourism"] == "attraction") return if (heritage || historic != null) PlaceKind.HISTORIC_BUILDING else PlaceKind.ATTRACTION
        if (heritage || historic != null) return PlaceKind.HISTORIC_BUILDING
        if (tags.containsKey("building")) return PlaceKind.BUILDING
        return null
    }

    private fun historicKind(historic: String?, tags: Map<String, String>): PlaceKind? = when (historic) {
        "castle" -> when (tags["castle_type"]) {
            "palace", "stately", "manor" -> PlaceKind.PALACE
            "fortress", "citadel", "defensive" -> PlaceKind.FORT
            else -> PlaceKind.CASTLE
        }
        "palace" -> PlaceKind.PALACE
        "fort", "fortress", "citadel" -> PlaceKind.FORT
        "city_gate" -> PlaceKind.CITY_GATE
        "citywalls" -> PlaceKind.CITY_WALL
        "monastery" -> PlaceKind.MONASTERY
        "manor" -> PlaceKind.MANOR
        "ruins" -> PlaceKind.RUINS
        "archaeological_site" -> PlaceKind.ARCHAEOLOGICAL_SITE
        "monument" -> PlaceKind.MONUMENT
        "memorial" -> when (tags["memorial"]) {
            "statue", "bust" -> PlaceKind.STATUE
            "stolperstein", "stumbling_stone", "plaque", "blue_plaque", "ghost_bike" -> null
            else -> PlaceKind.MEMORIAL
        }
        "ship", "wreck" -> PlaceKind.SHIP
        "windmill" -> PlaceKind.WINDMILL
        "watermill" -> PlaceKind.WATERMILL
        "tower" -> PlaceKind.TOWER
        "church" -> churchKind(tags)
        else -> null
    }

    private fun amenityKind(tags: Map<String, String>): PlaceKind? = when (tags["amenity"]) {
        "townhall" -> PlaceKind.TOWN_HALL
        "place_of_worship" -> when (tags["religion"]) {
            "muslim" -> PlaceKind.MOSQUE
            "jewish" -> PlaceKind.SYNAGOGUE
            "buddhist", "hindu", "sikh", "jain", "shinto", "taoist" -> PlaceKind.TEMPLE
            else -> churchKind(tags)
        }
        "library" -> PlaceKind.LIBRARY
        "university" -> PlaceKind.UNIVERSITY
        "college" -> PlaceKind.COLLEGE
        "courthouse" -> PlaceKind.COURTHOUSE
        "theatre" -> PlaceKind.THEATRE
        "concert_hall" -> PlaceKind.CONCERT_HALL
        "arts_centre" -> PlaceKind.ARTS_CENTRE
        "monastery" -> PlaceKind.MONASTERY
        "fountain" -> PlaceKind.FOUNTAIN
        else -> null
    }

    private fun churchKind(tags: Map<String, String>): PlaceKind = when (tags["building"]) {
        "cathedral" -> PlaceKind.CATHEDRAL
        "chapel" -> PlaceKind.CHAPEL
        else -> PlaceKind.CHURCH
    }

    private fun buildingKind(building: String?): PlaceKind? = when (building) {
        "cathedral" -> PlaceKind.CATHEDRAL
        "church" -> PlaceKind.CHURCH
        "chapel" -> PlaceKind.CHAPEL
        "mosque" -> PlaceKind.MOSQUE
        "synagogue" -> PlaceKind.SYNAGOGUE
        "temple", "shrine" -> PlaceKind.TEMPLE
        "townhall" -> PlaceKind.TOWN_HALL
        "castle" -> PlaceKind.CASTLE
        "palace" -> PlaceKind.PALACE
        "train_station" -> PlaceKind.STATION
        "university" -> PlaceKind.UNIVERSITY
        "windmill" -> PlaceKind.WINDMILL
        else -> null
    }

    private val denominations = mapOf(
        "catholic" to "Catholic", "roman_catholic" to "Catholic", "old_catholic" to "Old Catholic",
        "protestant" to "Protestant", "reformed" to "Protestant", "dutch_reformed" to "Protestant",
        "lutheran" to "Lutheran", "evangelical" to "Protestant", "anglican" to "Anglican",
        "orthodox" to "Orthodox", "greek_orthodox" to "Greek Orthodox", "russian_orthodox" to "Russian Orthodox",
        "baptist" to "Baptist", "methodist" to "Methodist", "mennonite" to "Mennonite", "remonstrant" to "Remonstrant",
    )

    /** What to call it when nothing better is known: "Catholic church", "town hall"… */
    fun noun(kind: PlaceKind, tags: Map<String, String>): String {
        val christian = kind == PlaceKind.CHURCH || kind == PlaceKind.CATHEDRAL || kind == PlaceKind.CHAPEL
        val adjective = if (christian) denominations[tags["denomination"]] else null
        return listOfNotNull(adjective, kind.noun).joinToString(" ")
    }
}
