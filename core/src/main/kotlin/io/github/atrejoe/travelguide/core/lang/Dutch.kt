package io.github.atrejoe.travelguide.core.lang

import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.RelativePosition
import io.github.atrejoe.travelguide.core.story.SectionKind
import kotlin.math.roundToInt

/** Dutch stories from the Dutch Wikipedia (issue #68). */
object Dutch : StoryLanguage("nl", "Dutch", "Nederlands") {
    override val sectionRules = listOf(
        SectionKind.SKIP to listOf(
            "demografie", "bevolking", "inwoners", "bestuur", "politiek", "economie", "verkeer", "vervoer",
            "onderwijs", "sport", "stedenband", "zustersted", "partnersted", "referentie", "bronnen", "noten",
            "literatuur", "externe link", "zie ook", "galerij", "bibliografie", "geboren", "bekende",
            "personen", "infrastructuur", "media", "gezondheid", "statistiek", "wijken", "buurten",
            "kernen", "lijst van", "voetnoten",
        ),
        SectionKind.NAME to listOf("etymologie", "naam", "toponymie"),
        SectionKind.LEGEND to listOf("legende", "sage", "folklore", "mythe", "spook", "mysterie"),
        SectionKind.HISTORY to listOf(
            "geschiedenis", "historie", "oorsprong", "ontstaan", "stichting", "middeleeuw", "oorlog", "oudheid",
            "prehistorie", "eeuw",
        ),
        SectionKind.CULTURE to listOf("cultuur", "kunst", "festival", "keuken", "eten", "muziek", "religie", "godsdienst", "traditie", "evenement"),
        SectionKind.LANDMARKS to listOf("bezienswaardig", "architectuur", "monument", "gebouw", "attractie", "erfgoed"),
        SectionKind.NATURE to listOf(
            "geografie", "aardrijkskunde", "geologie", "natuur", "landschap", "flora", "fauna", "ecologie", "milieu",
            "loop", "hydrologie", "ligging",
        ),
        SectionKind.CLIMATE to listOf("klimaat", "weer"),
    )

    override val abbreviations = setOf(
        "st", "dr", "mr", "ir", "ing", "drs", "prof", "mevr", "dhr", "ca", "c", "bijv", "bv", "o.a", "m.a.w",
        "e.d", "enz", "etc", "d.w.z", "nr", "km", "n.chr", "v.chr", "fig", "z.g", "i.p.v", "t.o.v", "resp",
    )
    override val sentenceStarters = setOf(
        "De", "Het", "Een", "In", "Dit", "Deze", "Hij", "Zij", "Ze", "Zijn", "Haar", "Na", "Voor",
        "Tijdens", "Toen", "Sinds", "Vandaag", "Echter", "Op", "Bij", "Door", "Van", "Er", "Ook",
    )

    override val remarkable = Regex(
        "\\b(oudste|grootste|hoogste|langste|diepste|kleinste|eerste|enige|laatste|beroemde?|bekend als|" +
            "bijnaam|bijgenaamd|opmerkelijke?|unieke?|legende|legendarische?|sage|mythe|volgens|" +
            "verwoest|afgebrand|brand|overstroming|watersnood|pest|beleg|belegerd|slag|veldslag|verdrag|" +
            "ontdekt|uitgevonden|overleefde?|wonder|geheim|mysterie|koning|koningin|keizer|romeinse?|romeinen|" +
            "vikingen|middeleeuwse?|monniken|monnik|ridders?|heilige|pelgrims?|schat|ruïnes?)\\b",
        RegexOption.IGNORE_CASE,
    )
    override val dramatic = Regex(
        "(?<!\\p{L})(vergiftig|bloedbad|moord|vermoord|terechtgesteld|onthoofd|heks|ramp|catastrofe|ontploffing|explosie|neergestort|schipbreuk|gezonken|aardbeving|lawine|epidemie|uitbraak|pest|hongersnood|oproer|opstand|muiterij|beleg|veldslag|overval|ontvoer|smokkel|ontsnap|schandaal|mysterie|raadsel|spook|vervloek|wonder|verschijning|tragedie)",
        RegexOption.IGNORE_CASE,
    )
    override val nameOrigin = Regex(
        "\\b(genoemd naar|vernoemd naar|ontleend aan|afgeleid van|komt van|de naam|betekent|betekenis)\\b",
        RegexOption.IGNORE_CASE,
    )
    override val dated = Regex("\\b(1[0-9]{3}|20[0-2][0-9]|[0-9]{1,2}e eeuw|v\\.Chr|n\\.Chr)\\b")
    override val administrative = Regex(
        "\\b(inwoners|bevolking|volkstelling|in de gemeente|deel van de gemeente|km2|km²|vierkante kilometer|" +
            "postcode|netnummer|bevolkingsdichtheid|procent|huishoudens|gemiddelde|bestuurlijk|hoofdplaats|" +
            "statistiek|gekozen|gemeenteraad|burgemeester|coördinaten|ligt in de provincie|bestaat uit de)\\b",
        RegexOption.IGNORE_CASE,
    )

    // Unlike English "It", "Het" is also an article ("Het kasteel …"), so it doesn't count here.
    override val contextDependent = Regex("^(Dit|Deze|Die|Zij|Ze|Hij|Zijn|Haar|Hun|Echter|Maar|Ook|Beide|Daar|Daarom)\\b")

    private val definitionVerbs = listOf(" is een", " is de", " is het", " was een", " was de", " was het", " zijn ", " is één")

    override fun isDefinition(sentence: String, title: String): Boolean {
        val head = sentence.take(title.length + 40).lowercase()
        val name = title.lowercase()
        return sentence.length < 220 &&
            (head.startsWith(name) || head.startsWith("de $name") || head.startsWith("het $name")) &&
            definitionVerbs.any { it in head }
    }

    /** Dutch descriptions are lower case ("dorp in Utrecht, Nederland"), and "een" fits every noun. */
    override fun identityLine(description: String): String {
        val lowered = description.replaceFirstChar { it.lowercase() }
        return if (lowered.startsWith("de ") || lowered.startsWith("het ")) "Het is $lowered." else "Het is een $lowered."
    }

    override fun leads(kind: SectionKind): List<String> = when (kind) {
        SectionKind.NAME -> listOf("Ooit afgevraagd waar de naam vandaan komt?", "De naam heeft een eigen verhaal.")
        SectionKind.LEGEND -> listOf("Er is ook een legende.", "En dan is er nog het verhaal dat mensen vertellen.")
        SectionKind.HISTORY -> listOf("Een stukje geschiedenis.", "Laten we teruggaan in de tijd.", "De geschiedenis heeft hier haar sporen nagelaten.")
        SectionKind.CULTURE -> listOf("Cultuur zit hier diep.", "Iets over het leven hier.")
        SectionKind.LANDMARKS -> listOf("Kijk goed, dan zie je dit.", "Een detail dat de moeite waard is.")
        SectionKind.NATURE -> listOf("Dan het landschap.", "De natuur heeft hier een eigen verhaal.")
        SectionKind.CLIMATE -> listOf("Wat het weer hier betreft.")
        else -> emptyList()
    }

    override val interestKeywords = mapOf(
        Interest.HISTORY to listOf(
            "slag", "kasteel", "fort", "oorlog", "middeleeuw", "romein", "eeuw", "histori", "ruïne", "monument",
            "gedenk", "archeolog", "abdij", "paleis", "gesticht", "opgericht", "beleg", "klooster", "havezate",
            "landgoed", "dynastie", "viking", "prehistor", "graf", "terp", "vesting", "wal", "citadel", "erfgoed",
            "voormalig", "gesloopt", "herbouwd",
        ),
        Interest.NATURE to listOf(
            "rivier", "meer", "berg", "bos", "natuurgebied", "natuurreservaat", "nationaal park", "dal", "heuvel",
            "eiland", "gletsjer", "duin", "heide", "moeras", "vogel", "soort", "klimaat", "geolog",
        ),
        Interest.CULTURE to listOf(
            "museum", "festival", "theater", "schouwburg", "kunst", "muziek", "schilder", "schrijver", "keuken",
            "traditie", "religi", "kerk", "kathedraal", "moskee", "tempel", "universiteit",
        ),
        Interest.ARCHITECTURE to listOf(
            "gebouw", "toren", "brug", "architect", "molen", "huis", "kasteel", "kerk", "kathedraal", "station",
            "gracht", "kanaal", "sluis", "dam", "wolkenkrabber",
        ),
    )
    override val placeNouns = setOf(
        "stad", "dorp", "plaats", "gemeente", "hoofdstad", "provincie", "regio", "graafschap", "district", "wijk",
        "buurt", "buurtschap", "gehucht", "kern", "woonplaats", "voormalige", "deelgemeente", "stadsdeel",
    )
    override val notPlaceNouns = setOf("kerk", "kasteel", "fort", "molen")

    override fun hereOpeners(title: String, area: Boolean, flying: Boolean): List<String> = when {
        area && flying -> listOf("Onder ons ligt nu $title.", "We vliegen over $title.")
        area -> listOf(
            "Welkom in $title.",
            "We zijn net aangekomen in $title.",
            "Je reist nu door $title.",
            "En hier zijn we dan, in $title.",
        )
        else -> listOf(
            "Kijk eens om je heen: je bent vlak bij $title.",
            "Hier vlakbij is $title.",
            "Sta even stil. Dit is $title.",
        )
    }

    override fun aheadOpeners(title: String, distance: String) = listOf(
        "Recht voor ons, op zo'n $distance, ligt $title.",
        "Hou de weg voor je in de gaten: over ongeveer $distance bereiken we $title.",
        "Een stukje verderop, zo'n $distance hiervandaan, ligt $title.",
    )

    override fun sideOpeners(title: String, left: Boolean, distance: String, window: Boolean): List<String> {
        val side = if (left) "links" else "rechts"
        val hand = if (left) "linkerhand" else "rechterhand"
        val windowSide = if (left) "linkerkant" else "rechterkant"
        return if (window) {
            listOf(
                "Kijk uit het raam aan de $windowSide. Op zo'n $distance ligt $title.",
                "Zit je aan de $windowSide bij het raam? Kijk dan nu: op zo'n $distance ligt $title.",
            )
        } else {
            listOf(
                "Kijk naar $side. Op zo'n $distance ligt $title.",
                "Aan je $hand, op ongeveer $distance, ligt $title.",
                "Naar $side, zo'n $distance hiervandaan, vind je $title.",
            )
        }
    }

    override fun behindOpeners(title: String, distance: String) =
        listOf("Vlak achter ons, op zo'n $distance, ligt $title.", "We zijn net langs $title gekomen, zo'n $distance achter ons.")

    override fun compassOpeners(title: String, direction: String, distance: String) = listOf(
        "Op zo'n $distance naar het $direction ligt $title.",
        "Niet ver hiervandaan, $distance naar het $direction, ligt $title.",
    )

    override fun afieldOpeners(title: String, relative: RelativePosition): List<String> = when (relative) {
        RelativePosition.Here -> listOf("Om ons heen ligt $title.", "De streek waar we nu zijn, is $title.")
        is RelativePosition.Ahead -> {
            val d = distance(relative.distanceM)
            listOf("Verderop langs de route, zo'n $d voor ons, ligt $title.", "Nog uit het zicht, op ongeveer $d voor ons, ligt $title.")
        }
        is RelativePosition.Side -> {
            val d = distance(relative.distanceM)
            val side = if (relative.left) "links" else "rechts"
            val hand = if (relative.left) "linkerhand" else "rechterhand"
            listOf("Iets verder weg, op zo'n $d aan je $hand, ligt $title.", "Uit het zicht, op zo'n $d naar $side, ligt $title.")
        }
        is RelativePosition.Behind -> {
            val d = distance(relative.distanceM)
            listOf("Zo'n $d achter ons ligt $title.", "Terug langs de weg die we kwamen, op ongeveer $d, ligt $title.")
        }
        is RelativePosition.Compass -> {
            val d = distance(relative.distanceM)
            val direction = compassName(relative.bearingDeg)
            listOf("Iets verder weg, op zo'n $d naar het $direction, ligt $title.", "Uit het zicht, op zo'n $d naar het $direction, ligt $title.")
        }
    }

    override fun transitions(title: String, where: String, mentioned: Boolean): List<String> = if (mentioned) {
        listOf("Wat ons brengt bij $title, $where.", "En dat verhaal gaat verder bij $title, $where.")
    } else {
        listOf(
            "Iets verderop, $where, vind je $title.",
            "Niet ver daarvandaan, $where, ligt $title.",
            "En nu we hier toch zijn: $where ligt $title.",
        )
    }

    override fun closings(relative: RelativePosition, mode: TravelMode): List<String> = when {
        relative is RelativePosition.Behind -> emptyList()
        mode == TravelMode.WALKING || mode == TravelMode.STATIONARY ->
            listOf("Neem even de tijd om rond te kijken.", "Het bekijken waard, als je de tijd hebt.")
        mode == TravelMode.CYCLING -> listOf("Een stop waard, als je benen het toelaten.", "Iets om naar uit te kijken onderweg.")
        relative is RelativePosition.Here -> listOf("Hou je ogen open terwijl we erdoorheen gaan.")
        else -> listOf("Hou het in de gaten als we erlangs komen.", "Iets om te onthouden voor een volgend bezoek.")
    }

    override fun where(relative: RelativePosition): String = when (relative) {
        RelativePosition.Here -> "hier"
        is RelativePosition.Ahead -> "recht voor ons, op zo'n ${distance(relative.distanceM)}"
        is RelativePosition.Side -> "aan je ${if (relative.left) "linkerhand" else "rechterhand"}, op zo'n ${distance(relative.distanceM)}"
        is RelativePosition.Behind -> "vlak achter ons"
        is RelativePosition.Compass -> "${distance(relative.distanceM)} naar het ${compassName(relative.bearingDeg)}"
    }

    override fun distance(meters: Double): String = when {
        meters < 950 -> "${((meters / 100).roundToInt() * 100).coerceAtLeast(100)} meter"
        meters < 1_050 -> "een kilometer"
        meters < 9_950 -> {
            val km = (meters / 100).roundToInt() / 10.0
            val text = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString().replace('.', ',')
            "$text kilometer"
        }
        else -> "${(meters / 1000).roundToInt()} kilometer"
    }

    override val compassNames = listOf("noorden", "noordoosten", "oosten", "zuidoosten", "zuiden", "zuidwesten", "westen", "noordwesten")

    override fun placeNoun(kind: PlaceKind): String = when (kind) {
        PlaceKind.TOWN_HALL -> "stadhuis"
        PlaceKind.CATHEDRAL -> "kathedraal"
        PlaceKind.CHURCH -> "kerk"
        PlaceKind.CHAPEL -> "kapel"
        PlaceKind.MOSQUE -> "moskee"
        PlaceKind.SYNAGOGUE -> "synagoge"
        PlaceKind.TEMPLE -> "tempel"
        PlaceKind.MONASTERY -> "klooster"
        PlaceKind.MUSEUM -> "museum"
        PlaceKind.GALLERY -> "kunstgalerie"
        PlaceKind.LIBRARY -> "bibliotheek"
        PlaceKind.UNIVERSITY -> "universiteitsgebouw"
        PlaceKind.COLLEGE -> "hogeschool"
        PlaceKind.COURTHOUSE -> "gerechtsgebouw"
        PlaceKind.THEATRE -> "theater"
        PlaceKind.CONCERT_HALL -> "concertzaal"
        PlaceKind.ARTS_CENTRE -> "kunstcentrum"
        PlaceKind.STATION -> "treinstation"
        PlaceKind.CASTLE -> "kasteel"
        PlaceKind.PALACE -> "paleis"
        PlaceKind.FORT -> "vesting"
        PlaceKind.CITY_GATE -> "stadspoort"
        PlaceKind.CITY_WALL -> "stadsmuur"
        PlaceKind.TOWER -> "toren"
        PlaceKind.WINDMILL -> "windmolen"
        PlaceKind.WATERMILL -> "watermolen"
        PlaceKind.LIGHTHOUSE -> "vuurtoren"
        PlaceKind.MANOR -> "landhuis"
        PlaceKind.RUINS -> "ruïne"
        PlaceKind.ARCHAEOLOGICAL_SITE -> "archeologische vindplaats"
        PlaceKind.MONUMENT -> "monument"
        PlaceKind.MEMORIAL -> "gedenkteken"
        PlaceKind.STATUE -> "standbeeld"
        PlaceKind.ARTWORK -> "kunstwerk"
        PlaceKind.FOUNTAIN -> "fontein"
        PlaceKind.SHIP -> "historisch schip"
        PlaceKind.HISTORIC_BUILDING -> "historisch gebouw"
        PlaceKind.BUILDING -> "gebouw"
        PlaceKind.ATTRACTION -> "bezienswaardigheid"
        PlaceKind.VIEWPOINT -> "uitzichtpunt"
    }

    // Church, cathedral and chapel are all "de"-words, which take the -e adjective.
    private val denominations = mapOf(
        "catholic" to "katholieke", "roman_catholic" to "katholieke", "old_catholic" to "oudkatholieke",
        "protestant" to "protestantse", "reformed" to "protestantse", "dutch_reformed" to "protestantse",
        "lutheran" to "lutherse", "evangelical" to "protestantse", "anglican" to "anglicaanse",
        "orthodox" to "orthodoxe", "greek_orthodox" to "Grieks-orthodoxe", "russian_orthodox" to "Russisch-orthodoxe",
        "baptist" to "baptistische", "methodist" to "methodistische", "mennonite" to "doopsgezinde", "remonstrant" to "remonstrantse",
    )

    override fun withDenomination(denomination: String?, noun: String): String =
        listOfNotNull(denominations[denomination], noun).joinToString(" ")

    override fun heritage(heritage: Heritage): String = when (heritage) {
        Heritage.WorldHeritage -> "onderdeel van UNESCO-werelderfgoed"
        Heritage.Rijksmonument -> "een rijksmonument"
        is Heritage.ListedStatus, Heritage.Listed -> "een beschermd monument"
        Heritage.National -> "een nationaal monument"
    }

    override fun theOne(kind: PlaceKind, description: String): String? {
        if (" van " !in description.substringBefore(',') || description.startsWith("de ") || description.startsWith("het ")) return null
        val article = if (kind == PlaceKind.TOWN_HALL || kind == PlaceKind.COURTHOUSE) "het" else "de"
        return "Het is $article ${description.replaceFirstChar { it.lowercase() }}."
    }

    override fun formerName(name: String) = listOf("Vroeger heette het $name.", "Het stond ooit bekend als $name.")

    override fun dateLines(kind: PlaceKind, date: HistoricDate, ago: String): List<String> {
        val inDate = inDate(date)
        val since = sinceDate(date)
        return when {
            kind.institution -> listOf("Het werd $inDate opgericht$ago.", "Het werd $inDate gesticht$ago.")
            kind.isObject -> listOf("Het werd $inDate hier geplaatst$ago.", "Het staat hier sinds $since.")
            else -> listOf("Het werd $inDate gebouwd$ago.", "Het dateert uit $since.", "Het staat hier al sinds $since.")
        }
    }

    /** "in 1488", "in de jaren 1480", "in de 15e eeuw". */
    private fun inDate(date: HistoricDate): String =
        if (date.precision == HistoricDate.Precision.YEAR) "in ${date.year}" else "in ${sinceDate(date)}"

    /** "1488", "de jaren 1480", "de 15e eeuw", to follow "sinds" or "dateert uit". */
    private fun sinceDate(date: HistoricDate): String = when (date.precision) {
        HistoricDate.Precision.YEAR -> "${date.year}"
        HistoricDate.Precision.DECADE -> "de jaren ${date.year / 10 * 10}"
        HistoricDate.Precision.CENTURY -> "de ${ordinal(date.century)} eeuw"
    }

    /** Spelled out, so every voice reads "de vijftiende eeuw" right. */
    private fun ordinal(n: Int): String = listOf(
        "eerste", "tweede", "derde", "vierde", "vijfde", "zesde", "zevende", "achtste", "negende", "tiende", "elfde",
        "twaalfde", "dertiende", "veertiende", "vijftiende", "zestiende", "zeventiende", "achttiende", "negentiende",
        "twintigste", "eenentwintigste",
    ).getOrNull(n - 1) ?: "${n}e"

    override fun ago(years: Int) = ", meer dan $years jaar geleden"

    override fun architectLines(kind: PlaceKind, names: String): List<String> = when {
        kind.isObject -> listOf("Het is het werk van $names.", "Het werd gemaakt door $names.")
        kind.institution -> listOf("Het gebouw werd ontworpen door $names.", "De architect van het gebouw was $names.")
        else -> listOf("Het werd ontworpen door $names.", "De architect was $names.")
    }

    override fun styleLines(style: String) =
        listOf("Let op de bouwstijl: $style.", "Het is gebouwd in een stijl die bekendstaat als $style.")

    override fun heritageLines(heritage: String) = listOf("Het is $heritage.", "Tegenwoordig is het beschermd als $heritage.")

    override fun namedAfterLines(name: String) = listOf("Het is vernoemd naar $name.", "De naam eert $name.")

    override fun occupantLines(name: String) = listOf("Tegenwoordig is hier $name gevestigd.", "Nu zit hier $name.")

    override fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " en " + names.last()
    }
}
