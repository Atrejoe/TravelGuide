package io.github.atrejoe.travelguide.core.lang

import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.RelativePosition
import io.github.atrejoe.travelguide.core.story.SectionKind
import kotlin.math.roundToInt

object English : StoryLanguage("en", "English", "English") {
    override val sectionRules = listOf(
        SectionKind.SKIP to listOf(
            "demograph", "population", "government", "politic", "administrat", "economy",
            "transport", "education", "sport", "twin", "sister cit", "reference", "notes",
            "further reading", "external link", "see also", "gallery", "bibliograph", "sources",
            "notable people", "people", "infrastructure", "media", "health", "statistic",
            "subdivision", "neighbourhood", "neighborhood", "district", "list of", "citations",
        ),
        SectionKind.NAME to listOf("etymolog", "name", "toponym"),
        SectionKind.LEGEND to listOf("legend", "folklore", "myth", "ghost", "mystery"),
        SectionKind.HISTORY to listOf("history", "origin", "founding", "medieval", "middle ages", "war", "ancient", "prehistor", "century"),
        SectionKind.CULTURE to listOf("culture", "art", "festival", "cuisine", "food", "music", "religio", "tradition", "event"),
        SectionKind.LANDMARKS to listOf("landmark", "architecture", "sight", "monument", "building", "attraction", "heritage"),
        SectionKind.NATURE to listOf(
            "geograph", "geolog", "nature", "landscape", "flora", "fauna", "wildlife", "ecolog", "environment", "course", "hydrolog",
        ),
        SectionKind.CLIMATE to listOf("climate", "weather"),
    )

    override val abbreviations = setOf(
        "st", "mt", "mr", "mrs", "ms", "dr", "prof", "jr", "sr", "vs", "c", "ca", "approx", "no",
        "e.g", "i.e", "etc", "u.s", "u.k", "a.d", "b.c", "inc", "ltd", "co", "ft", "km", "fig",
    )
    override val sentenceStarters = setOf(
        "The", "It", "In", "A", "An", "This", "These", "He", "She", "They", "His", "Her", "Its",
        "After", "Before", "During", "When", "Since", "Today", "However", "On", "At", "By", "From",
    )

    override val remarkable = Regex(
        "\\b(oldest|largest|biggest|tallest|longest|highest|deepest|smallest|first|only|last|famous|" +
            "renowned|remarkable|unique|legend|legendary|myth|according to|nicknamed|known as|" +
            "destroyed|burned|flood|plague|siege|battle|besieged|treaty|discovered|invented|" +
            "survived|miracle|secret|mystery|king|queen|emperor|roman|romans|viking|medieval|" +
            "monks?|knights?|saint|pilgrim|treasure|ruins?)\\b",
        RegexOption.IGNORE_CASE,
    )
    override val nameOrigin = Regex(
        "\\b(named after|derived from|derives from|comes from|the name|meaning|means)\\b",
        RegexOption.IGNORE_CASE,
    )
    override val dated = Regex("\\b(1[0-9]{3}|20[0-2][0-9]|[0-9]{1,2}(st|nd|rd|th) century|BC|AD)\\b")
    override val administrative = Regex(
        "\\b(population|inhabitants|census|municipality of|km2|km²|square kilomet|square mile|" +
            "postal code|postcode|area code|density|per cent|percent|households|median|" +
            "administrative|seat of|statistics|elected|council|mayor|coordinates|" +
            "is located in the province|lies in the province|consists of the)\\b",
        RegexOption.IGNORE_CASE,
    )
    override val contextDependent = Regex("^(It|This|These|They|He|She|His|Her|Its|However|But|Also|Both|There)\\b")

    private val definitionVerbs = listOf(" is a", " is an", " is the", " was a", " was an", " was the", " are a", " is one")

    override fun isDefinition(sentence: String, title: String): Boolean {
        val head = sentence.take(title.length + 40).lowercase()
        return sentence.length < 220 &&
            (head.startsWith(title.lowercase()) || head.startsWith("the " + title.lowercase())) &&
            definitionVerbs.any { it in head }
    }

    private val commonNouns = setOf(
        "city", "town", "village", "municipality", "river", "lake", "mountain", "hill", "church",
        "castle", "building", "museum", "station", "capital", "former", "historic", "national",
        "island", "bridge", "tower", "park", "district", "region", "province", "street", "square",
        "neighbourhood", "hamlet", "place", "area", "forest", "canal", "church", "branch", "beach",
        "arch", "urban", "suburban", "human", "public", "civic", "historic", "scenic", "gothic",
    )
    private val demonymSuffixes = listOf("ish", "ese", "ian", "an", "ch", "ic", "i")

    override fun identityLine(description: String): String? {
        val lowered = description.replaceFirstChar { it.lowercase() }
        val phrase = when {
            lowered.startsWith("the ") -> lowered
            // Keep proper adjectives capitalised ("a Dutch village", "a Roman fort"), but not
            // ordinary words that a short description happens to start with.
            looksLikeProperAdjective(description.substringBefore(' ')) -> description
            else -> lowered
        }
        val article = when {
            phrase.startsWith("the ") -> ""
            phrase.first().lowercaseChar() in "aeiou" -> "an "
            else -> "a "
        }
        return "It is $article$phrase."
    }

    private fun looksLikeProperAdjective(word: String): Boolean {
        if (word.isEmpty() || !word.first().isUpperCase()) return false
        val lower = word.lowercase()
        if (lower in commonNouns) return false
        return demonymSuffixes.any { lower.endsWith(it) }
    }

    override fun leads(kind: SectionKind): List<String> = when (kind) {
        SectionKind.NAME -> listOf("Ever wondered about the name?", "The name has a story of its own.")
        SectionKind.LEGEND -> listOf("There is a legend, too.", "And then there is the story people tell.")
        SectionKind.HISTORY -> listOf("A little history.", "Let's go back in time.", "History has left its mark here.")
        SectionKind.CULTURE -> listOf("Culture runs deep here.", "Something about local life.")
        SectionKind.LANDMARKS -> listOf("Look closely and you'll notice this.", "A detail worth knowing.")
        SectionKind.NATURE -> listOf("Now, about the landscape.", "Nature has its own story here.")
        SectionKind.CLIMATE -> listOf("As for the weather here.")
        else -> emptyList()
    }

    override val interestKeywords = mapOf(
        Interest.HISTORY to listOf(
            "battle", "castle", "fort", "war", "ancient", "medieval", "roman", "century", "historic",
            "ruin", "monument", "memorial", "archaeolog", "abbey", "palace", "founded", "siege",
            "monastery", "manor", "estate", "dynasty", "viking", "prehistoric", "burial", "mound",
            "fortress", "rampart", "citadel", "heritage", "former", "demolished", "rebuilt",
        ),
        Interest.NATURE to listOf(
            "river", "lake", "mountain", "forest", "nature reserve", "national park", "valley", "hill",
            "island", "glacier", "dune", "heath", "wetland", "bird", "species", "climate", "geolog",
        ),
        Interest.CULTURE to listOf(
            "museum", "festival", "theatre", "theater", "art", "music", "painter", "writer", "cuisine",
            "tradition", "religio", "church", "cathedral", "mosque", "temple", "university",
        ),
        Interest.ARCHITECTURE to listOf(
            "building", "tower", "bridge", "architect", "windmill", "mill", "house", "castle", "church",
            "cathedral", "station", "canal", "lock", "dam", "skyscraper",
        ),
    )
    override val placeNouns = setOf(
        "city", "town", "village", "municipality", "capital", "province", "region", "county",
        "district", "neighbourhood", "neighborhood", "hamlet", "commune", "borough", "parish",
        "former", "human", "place", "census-designated",
    )
    override val notPlaceNouns = setOf("church", "castle", "fort", "mill")

    override fun hereOpeners(title: String, area: Boolean, flying: Boolean): List<String> = when {
        area && flying -> listOf("Below us now lies $title.", "We are flying over $title.")
        area -> listOf(
            "Welcome to $title.",
            "We've just arrived in $title.",
            "You are now travelling through $title.",
            "And here we are, in $title.",
        )
        else -> listOf(
            "Take a look around: you are right next to $title.",
            "Right here is $title.",
            "Stop for a moment. This is $title.",
        )
    }

    override fun aheadOpeners(title: String, distance: String) = listOf(
        "Coming up ahead, about $distance away, is $title.",
        "Keep your eyes on the road ahead: in about $distance we reach $title.",
        "Just ahead, some $distance from here, lies $title.",
    )

    override fun sideOpeners(title: String, left: Boolean, distance: String, window: Boolean): List<String> {
        val side = if (left) "left" else "right"
        return if (window) {
            listOf(
                "Look out of the $side-hand window. About $distance away lies $title.",
                "If you have a $side-hand window seat, look out now: about $distance away is $title.",
            )
        } else {
            listOf(
                "Look to your $side. About $distance away is $title.",
                "Over on your $side, roughly $distance away, lies $title.",
                "Off to the $side, about $distance from here, you'll find $title.",
            )
        }
    }

    override fun behindOpeners(title: String, distance: String) =
        listOf("Just behind us, about $distance away, is $title.", "We have just passed $title, about $distance behind us.")

    override fun compassOpeners(title: String, direction: String, distance: String) = listOf(
        "About $distance to the $direction lies $title.",
        "Not far from here, $distance to the $direction, is $title.",
    )

    override fun afieldOpeners(title: String, relative: RelativePosition): List<String> = when (relative) {
        RelativePosition.Here -> listOf("All around us lies $title.", "The area we are in is $title.")
        is RelativePosition.Ahead -> {
            val d = distance(relative.distanceM)
            listOf("Further along the way, some $d ahead, lies $title.", "Out of sight for now, about $d ahead, is $title.")
        }
        is RelativePosition.Side -> {
            val side = if (relative.left) "left" else "right"
            val d = distance(relative.distanceM)
            listOf(
                "A little further afield, about $d to your $side, lies $title.",
                "Out of sight, some $d off to the $side, is $title.",
            )
        }
        is RelativePosition.Behind -> {
            val d = distance(relative.distanceM)
            listOf("Some $d behind us lies $title.", "Back the way we came, about $d away, is $title.")
        }
        is RelativePosition.Compass -> {
            val d = distance(relative.distanceM)
            val direction = compassName(relative.bearingDeg)
            listOf(
                "A little further afield, about $d to the $direction, lies $title.",
                "Out of sight, some $d to the $direction, is $title.",
            )
        }
    }

    override fun transitions(title: String, where: String, mentioned: Boolean): List<String> = if (mentioned) {
        listOf("Which brings us to $title, $where.", "And that story continues at $title, $where.")
    } else {
        listOf(
            "A little further along, $where, you'll find $title.",
            "Not far from there, $where, lies $title.",
            "And while we're here: $where is $title.",
        )
    }

    override fun closings(relative: RelativePosition, mode: TravelMode): List<String> = when {
        relative is RelativePosition.Behind -> emptyList()
        mode == TravelMode.WALKING || mode == TravelMode.STATIONARY ->
            listOf("Take a moment to look around.", "Worth a closer look, if you have the time.")
        mode == TravelMode.CYCLING -> listOf("Worth a stop, if your legs agree.", "Something to look out for as you ride.")
        relative is RelativePosition.Here -> listOf("Keep your eyes open as we pass through.")
        else -> listOf("Keep an eye out as we pass.", "Something to remember for a future visit.")
    }

    override fun where(relative: RelativePosition): String = when (relative) {
        RelativePosition.Here -> "right here"
        is RelativePosition.Ahead -> "straight ahead, about ${distance(relative.distanceM)} away"
        is RelativePosition.Side ->
            "on your ${if (relative.left) "left" else "right"}, about ${distance(relative.distanceM)} away"
        is RelativePosition.Behind -> "just behind us"
        is RelativePosition.Compass -> "${distance(relative.distanceM)} to the ${compassName(relative.bearingDeg)}"
    }

    override fun distance(meters: Double): String = when {
        meters < 950 -> "${((meters / 100).roundToInt() * 100).coerceAtLeast(100)} metres"
        meters < 1_050 -> "a kilometre"
        meters < 9_950 -> {
            val km = (meters / 100).roundToInt() / 10.0
            val text = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString()
            "$text kilometres"
        }
        else -> "${(meters / 1000).roundToInt()} kilometres"
    }

    override val compassNames = listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")

    override fun placeNoun(kind: PlaceKind): String = kind.noun

    private val denominations = mapOf(
        "catholic" to "Catholic", "roman_catholic" to "Catholic", "old_catholic" to "Old Catholic",
        "protestant" to "Protestant", "reformed" to "Protestant", "dutch_reformed" to "Protestant",
        "lutheran" to "Lutheran", "evangelical" to "Protestant", "anglican" to "Anglican",
        "orthodox" to "Orthodox", "greek_orthodox" to "Greek Orthodox", "russian_orthodox" to "Russian Orthodox",
        "baptist" to "Baptist", "methodist" to "Methodist", "mennonite" to "Mennonite", "remonstrant" to "Remonstrant",
    )

    override fun withDenomination(denomination: String?, noun: String): String =
        listOfNotNull(denominations[denomination], noun).joinToString(" ")

    override fun heritage(heritage: Heritage): String = when (heritage) {
        Heritage.WorldHeritage -> "part of a UNESCO World Heritage Site"
        Heritage.Rijksmonument -> "a Rijksmonument, a listed national monument"
        is Heritage.ListedStatus -> {
            val spoken = heritage.status.replace("*", " star").trim()
            if (spoken.contains("listed", ignoreCase = true)) withArticle(spoken) else withArticle("$spoken listed building")
        }
        Heritage.Listed -> "a listed monument"
        Heritage.National -> "a national monument"
    }

    fun withArticle(phrase: String): String =
        if (phrase.first().lowercaseChar() in "aeiou") "an $phrase" else "a $phrase"

    override fun theOne(kind: PlaceKind, description: String): String? {
        if (" of " !in description.substringBefore(',') || description.startsWith("the ", ignoreCase = true)) return null
        return "It is the ${description.replaceFirstChar { it.lowercase() }}."
    }

    override fun formerName(name: String) = listOf("It was once known as $name.", "In the past it was called $name.")

    override fun dateLines(kind: PlaceKind, date: HistoricDate, ago: String): List<String> = when {
        kind.institution -> listOf("It was founded ${date.inPhrase}$ago.", "It was established ${date.inPhrase}$ago.")
        kind.isObject -> listOf("It was placed here ${date.inPhrase}$ago.", "It has stood here since ${date.sincePhrase}.")
        else -> listOf(
            "It was built ${date.inPhrase}$ago.",
            "It dates from ${date.sincePhrase}.",
            "It has stood here since ${date.sincePhrase}.",
        )
    }

    override fun ago(years: Int) = ", more than $years years ago"

    override fun architectLines(kind: PlaceKind, names: String): List<String> = when {
        kind.isObject -> listOf("It is the work of $names.", "It was made by $names.")
        kind.institution -> listOf("Its building was designed by $names.", "The architect of its building was $names.")
        else -> listOf("It was designed by $names.", "The architect was $names.")
    }

    override fun styleLines(style: String): List<String> {
        if (style.endsWith(" architecture", ignoreCase = true)) {
            val base = style.dropLast(" architecture".length)
            return listOf("It is a fine example of $base architecture.", "Notice its $base architecture.")
        }
        return listOf("Its style is known as $style.", "It is built in a style known as $style.")
    }

    override fun heritageLines(heritage: String) = listOf("It is $heritage.", "Today it is protected as $heritage.")

    override fun namedAfterLines(name: String) = listOf("It is named after ${withThe(name)}.", "Its name honours ${withThe(name)}.")

    override fun occupantLines(name: String) =
        listOf("Today it is home to ${withThe(name)}.", "These days it houses ${withThe(name)}.")

    override fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /** "municipal council" -> "the municipal council"; proper names stay as they are. */
    fun withThe(label: String): String =
        if (label.firstOrNull()?.isLowerCase() == true && !label.startsWith("the ")) "the $label" else label
}
