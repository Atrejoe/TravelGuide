package io.github.atrejoe.travelguide.core.lang

import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.RelativePosition
import io.github.atrejoe.travelguide.core.story.SectionKind
import kotlin.math.roundToInt

/**
 * German stories from the German Wikipedia (issue #68), addressing the listener as "Sie".
 * Phrases avoid naming a place's gender: "Es" for the building, the title on its own as subject.
 */
object German : StoryLanguage("de", "German", "Deutsch") {
    override val sectionRules = listOf(
        SectionKind.SKIP to listOf(
            "einwohner", "bevölkerung", "demografie", "demographie", "politik", "verwaltung", "wirtschaft", "verkehr",
            "bildung", "sport", "partnerst", "städtepartner", "einzelnachweis", "literatur", "weblinks", "siehe auch",
            "galerie", "persönlichkeiten", "söhne und töchter", "infrastruktur", "medien", "gesundheit", "statistik",
            "ortsteile", "stadtteile", "gliederung", "liste", "anmerkungen", "quellen",
        ),
        SectionKind.NAME to listOf("name", "etymologie"),
        SectionKind.LEGEND to listOf("sage", "legende", "mythos", "spuk"),
        SectionKind.HISTORY to listOf("geschichte", "historie", "ursprung", "gründung", "mittelalter", "krieg", "antike", "vorgeschichte", "jahrhundert"),
        SectionKind.CULTURE to listOf("kultur", "kunst", "fest", "küche", "musik", "religion", "tradition", "brauchtum", "veranstaltung"),
        SectionKind.LANDMARKS to listOf("sehenswürdigkeit", "architektur", "denkmal", "bauwerk", "gebäude", "baubeschreibung"),
        SectionKind.NATURE to listOf("geographie", "geografie", "geologie", "natur", "landschaft", "flora", "fauna", "lage", "verlauf", "hydrologie"),
        SectionKind.CLIMATE to listOf("klima", "wetter"),
    )

    override val abbreviations = setOf(
        "z.b", "bzw", "ca", "usw", "u.a", "d.h", "st", "dr", "prof", "nr", "jh", "jhd", "v.chr", "n.chr", "chr", "evtl",
        "ggf", "inkl", "km", "sog", "vgl", "z.t", "abb", "geb", "gest", "hl", "s",
    )
    override val sentenceStarters = setOf(
        "Der", "Die", "Das", "Ein", "Eine", "Es", "Er", "Sie", "In", "Im", "Nach", "Vor", "Seit", "Heute", "Am",
        "Bei", "Mit", "Von", "Auf", "Als", "Dies", "Diese", "Damit",
    )
    override val ordinalDots = true

    // German compounds ("Stadtbrand", "Römerkastell") hide words inside others: no word boundaries.
    override val remarkable = Regex(
        "(älteste|größte|höchste|längste|tiefste|kleinste|erste[nrs]?\\b|einzige|letzte[nrs]?\\b|berühmt|bekannt als|beiname|" +
            "legende|sage\\b|mythos|der überlieferung nach|zerstört|abgebrannt|brand|hochwasser|sturmflut|pest|belagerung|" +
            "belagert|schlacht|vertrag|entdeckt|erfunden|überlebt|wunder|geheimnis|könig|kaiser|römer|römisch|wikinger|" +
            "mittelalterlich|mönch|ritter|heilige|pilger|schatz|ruine)",
        RegexOption.IGNORE_CASE,
    )
    override val nameOrigin = Regex("(benannt nach|abgeleitet|geht zurück auf|der name|bedeutet|bedeutung)", RegexOption.IGNORE_CASE)
    override val dated = Regex("(\\b1[0-9]{3}\\b|\\b20[0-2][0-9]\\b|[0-9]{1,2}\\. jahrhundert|v\\. ?chr|n\\. ?chr)", RegexOption.IGNORE_CASE)
    override val administrative = Regex(
        "(einwohner|bevölkerung|volkszählung|ortsgemeinde|verbandsgemeinde|gemeindegebiet|km²|quadratkilometer|postleitzahl|" +
            "vorwahl|prozent|haushalte|verwaltungssitz|kreisstadt|statistik|gewählt|gemeinderat|bürgermeister|koordinaten|" +
            "liegt im landkreis|besteht aus den)",
        RegexOption.IGNORE_CASE,
    )
    override val contextDependent = Regex("^(Er|Sie|Es|Dies|Diese|Dieser|Dieses|Sein|Seine|Ihr|Ihre|Jedoch|Aber|Auch|Beide|Dort|Daher|Damit)\\b")

    private val definitionVerbs = listOf(" ist ein", " ist der", " ist die", " ist das", " war ein", " war der", " war die", " sind ")

    override fun isDefinition(sentence: String, title: String): Boolean {
        val head = sentence.take(title.length + 40).lowercase()
        val name = title.lowercase()
        return sentence.length < 220 &&
            (head.startsWith(name) || listOf("der ", "die ", "das ").any { head.startsWith(it + name) }) &&
            definitionVerbs.any { it in head }
    }

    /** German descriptions ("Stadt in Bayern") don't say their gender; the article's first sentence does. */
    override fun identityLine(description: String): String? = null

    override fun kindLine(kind: PlaceKind) = "Es ist ${placeNoun(kind)}."

    override fun leads(kind: SectionKind): List<String> = when (kind) {
        SectionKind.NAME -> listOf("Woher kommt eigentlich der Name?", "Der Name hat seine eigene Geschichte.")
        SectionKind.LEGEND -> listOf("Es gibt auch eine Sage.", "Und dann ist da noch die Geschichte, die man sich erzählt.")
        SectionKind.HISTORY -> listOf("Ein wenig Geschichte.", "Gehen wir zurück in der Zeit.", "Die Geschichte hat hier ihre Spuren hinterlassen.")
        SectionKind.CULTURE -> listOf("Die Kultur hat hier tiefe Wurzeln.", "Etwas über das Leben hier.")
        SectionKind.LANDMARKS -> listOf("Wer genau hinsieht, bemerkt Folgendes.", "Ein Detail, das man kennen sollte.")
        SectionKind.NATURE -> listOf("Nun zur Landschaft.", "Die Natur hat hier ihre eigene Geschichte.")
        SectionKind.CLIMATE -> listOf("Was das Wetter angeht.")
        else -> emptyList()
    }

    override val interestKeywords = mapOf(
        Interest.HISTORY to listOf(
            "schlacht", "burg", "festung", "krieg", "antik", "mittelalter", "römer", "jahrhundert", "histori", "ruine",
            "denkmal", "gedenk", "archäolog", "abtei", "kloster", "schloss", "palast", "gegründet", "belagerung",
            "herrenhaus", "dynastie", "wikinger", "vorgeschicht", "grab", "zitadelle", "erbe", "ehemalig", "abgerissen",
            "wiederaufgebaut",
        ),
        Interest.NATURE to listOf(
            "fluss", "see", "berg", "wald", "naturschutzgebiet", "nationalpark", "tal", "hügel", "insel", "gletscher",
            "düne", "heide", "moor", "vogel", "klima", "geolog",
        ),
        Interest.CULTURE to listOf(
            "museum", "festival", "theater", "kunst", "musik", "maler", "schriftsteller", "küche", "tradition", "religi",
            "kirche", "dom", "kathedrale", "moschee", "tempel", "universität",
        ),
        Interest.ARCHITECTURE to listOf(
            "gebäude", "turm", "brücke", "architekt", "windmühle", "mühle", "haus", "burg", "kirche", "dom", "bahnhof",
            "kanal", "schleuse", "damm", "hochhaus",
        ),
    )
    override val placeNouns = setOf(
        "stadt", "dorf", "ort", "gemeinde", "ortsteil", "hauptstadt", "landeshauptstadt", "kreisstadt", "bundesland",
        "region", "landkreis", "kreis", "bezirk", "stadtteil", "ortschaft", "weiler", "ehemalige", "kleinstadt",
        "marktgemeinde",
    )
    override val notPlaceNouns = setOf("kirche", "burg", "festung", "mühle")

    override fun hereOpeners(title: String, area: Boolean, flying: Boolean): List<String> = when {
        area && flying -> listOf("Unter uns liegt jetzt $title.", "Wir überfliegen gerade $title.")
        area -> listOf(
            "Willkommen in $title.",
            "Wir sind gerade in $title angekommen.",
            "Sie reisen jetzt durch $title.",
            "Und da sind wir: in $title.",
        )
        else -> listOf(
            "Schauen Sie sich um: Sie sind direkt bei $title.",
            "Genau hier befindet sich $title.",
            "Halten Sie kurz inne. Das hier ist $title.",
        )
    }

    override fun aheadOpeners(title: String, distance: String) = listOf(
        "Vor uns, etwa $distance entfernt, liegt $title.",
        "Behalten Sie die Straße im Blick: $title ist nur noch etwa $distance entfernt.",
        "Ein Stück voraus, rund $distance von hier, liegt $title.",
    )

    override fun sideOpeners(title: String, left: Boolean, distance: String, window: Boolean): List<String> {
        val direction = if (left) "links" else "rechts"
        val hand = if (left) "Linken" else "Rechten"
        val adjective = if (left) "linken" else "rechten"
        return if (window) {
            listOf(
                "Schauen Sie aus dem $adjective Fenster. Etwa $distance entfernt liegt $title.",
                "Wenn Sie auf der $adjective Seite sitzen, schauen Sie jetzt hinaus: Etwa $distance entfernt liegt $title.",
            )
        } else {
            listOf(
                "Schauen Sie nach $direction. Etwa $distance entfernt liegt $title.",
                "Zu Ihrer $hand, rund $distance entfernt, liegt $title.",
                "Etwas abseits nach $direction, etwa $distance von hier, finden Sie $title.",
            )
        }
    }

    override fun behindOpeners(title: String, distance: String) =
        listOf("Direkt hinter uns, etwa $distance entfernt, liegt $title.", "Wir sind gerade an $title vorbeigekommen, etwa $distance hinter uns.")

    override fun compassOpeners(title: String, direction: String, distance: String) = listOf(
        "Etwa $distance nach $direction liegt $title.",
        "Nicht weit von hier, $distance in Richtung $direction, liegt $title.",
    )

    override fun afieldOpeners(title: String, relative: RelativePosition): List<String> = when (relative) {
        RelativePosition.Here -> listOf("Rings um uns liegt $title.", "Die Gegend, in der wir sind, ist $title.")
        is RelativePosition.Ahead -> {
            val d = distance(relative.distanceM)
            listOf("Weiter vorn an der Strecke, etwa $d voraus, liegt $title.", "Noch außer Sicht, rund $d voraus, liegt $title.")
        }
        is RelativePosition.Side -> {
            val d = distance(relative.distanceM)
            val direction = if (relative.left) "links" else "rechts"
            val hand = if (relative.left) "Linken" else "Rechten"
            listOf("Etwas weiter entfernt, etwa $d zu Ihrer $hand, liegt $title.", "Außer Sicht, rund $d nach $direction, liegt $title.")
        }
        is RelativePosition.Behind -> {
            val d = distance(relative.distanceM)
            listOf("Etwa $d hinter uns liegt $title.", "Den Weg zurück, rund $d entfernt, liegt $title.")
        }
        is RelativePosition.Compass -> {
            val d = distance(relative.distanceM)
            val direction = compassName(relative.bearingDeg)
            listOf("Etwas weiter entfernt, etwa $d nach $direction, liegt $title.", "Außer Sicht, rund $d nach $direction, liegt $title.")
        }
    }

    override fun transitions(title: String, where: String, mentioned: Boolean): List<String> = if (mentioned) {
        listOf("Was uns zu $title führt, $where.", "Und die Geschichte geht weiter bei $title, $where.")
    } else {
        listOf(
            "Etwas weiter, $where, finden Sie $title.",
            "Nicht weit davon, $where, liegt $title.",
            "Und wo wir schon hier sind: $where liegt $title.",
        )
    }

    override fun closings(relative: RelativePosition, mode: TravelMode): List<String> = when {
        relative is RelativePosition.Behind -> emptyList()
        mode == TravelMode.WALKING || mode == TravelMode.STATIONARY ->
            listOf("Nehmen Sie sich einen Moment Zeit, sich umzusehen.", "Einen genaueren Blick wert, wenn Sie Zeit haben.")
        mode == TravelMode.CYCLING -> listOf("Einen Halt wert, wenn die Beine mitspielen.", "Etwas, worauf man beim Radeln achten kann.")
        relative is RelativePosition.Here -> listOf("Halten Sie die Augen offen, während wir hindurchfahren.")
        else -> listOf("Halten Sie Ausschau, wenn wir vorbeikommen.", "Etwas für einen späteren Besuch.")
    }

    override fun where(relative: RelativePosition): String = when (relative) {
        RelativePosition.Here -> "genau hier"
        is RelativePosition.Ahead -> "geradeaus, etwa ${distance(relative.distanceM)} entfernt"
        is RelativePosition.Side -> "zu Ihrer ${if (relative.left) "Linken" else "Rechten"}, etwa ${distance(relative.distanceM)} entfernt"
        is RelativePosition.Behind -> "direkt hinter uns"
        is RelativePosition.Compass -> "${distance(relative.distanceM)} nach ${compassName(relative.bearingDeg)}"
    }

    override fun distance(meters: Double): String = when {
        meters < 950 -> "${((meters / 100).roundToInt() * 100).coerceAtLeast(100)} Meter"
        meters < 1_050 -> "ein Kilometer"
        meters < 9_950 -> {
            val km = (meters / 100).roundToInt() / 10.0
            val text = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString().replace('.', ',')
            "$text Kilometer"
        }
        else -> "${(meters / 1000).roundToInt()} Kilometer"
    }

    override val compassNames = listOf("Norden", "Nordosten", "Osten", "Südosten", "Süden", "Südwesten", "Westen", "Nordwesten")

    /** With its indefinite article, which depends on the noun's gender. */
    override fun placeNoun(kind: PlaceKind): String = when (kind) {
        PlaceKind.TOWN_HALL -> "ein Rathaus"
        PlaceKind.CATHEDRAL -> "ein Dom"
        PlaceKind.CHURCH -> "eine Kirche"
        PlaceKind.CHAPEL -> "eine Kapelle"
        PlaceKind.MOSQUE -> "eine Moschee"
        PlaceKind.SYNAGOGUE -> "eine Synagoge"
        PlaceKind.TEMPLE -> "ein Tempel"
        PlaceKind.MONASTERY -> "ein Kloster"
        PlaceKind.MUSEUM -> "ein Museum"
        PlaceKind.GALLERY -> "eine Kunstgalerie"
        PlaceKind.LIBRARY -> "eine Bibliothek"
        PlaceKind.UNIVERSITY -> "ein Universitätsgebäude"
        PlaceKind.COLLEGE -> "eine Hochschule"
        PlaceKind.COURTHOUSE -> "ein Gerichtsgebäude"
        PlaceKind.THEATRE -> "ein Theater"
        PlaceKind.CONCERT_HALL -> "ein Konzertsaal"
        PlaceKind.ARTS_CENTRE -> "ein Kulturzentrum"
        PlaceKind.STATION -> "ein Bahnhof"
        PlaceKind.CASTLE -> "eine Burg"
        PlaceKind.PALACE -> "ein Schloss"
        PlaceKind.FORT -> "eine Festung"
        PlaceKind.CITY_GATE -> "ein Stadttor"
        PlaceKind.CITY_WALL -> "eine Stadtmauer"
        PlaceKind.TOWER -> "ein Turm"
        PlaceKind.WINDMILL -> "eine Windmühle"
        PlaceKind.WATERMILL -> "eine Wassermühle"
        PlaceKind.LIGHTHOUSE -> "ein Leuchtturm"
        PlaceKind.MANOR -> "ein Herrenhaus"
        PlaceKind.RUINS -> "eine Ruine"
        PlaceKind.ARCHAEOLOGICAL_SITE -> "eine archäologische Fundstätte"
        PlaceKind.MONUMENT -> "ein Denkmal"
        PlaceKind.MEMORIAL -> "eine Gedenkstätte"
        PlaceKind.STATUE -> "eine Statue"
        PlaceKind.ARTWORK -> "ein Kunstwerk"
        PlaceKind.FOUNTAIN -> "ein Brunnen"
        PlaceKind.SHIP -> "ein historisches Schiff"
        PlaceKind.HISTORIC_BUILDING -> "ein historisches Gebäude"
        PlaceKind.BUILDING -> "ein Gebäude"
        PlaceKind.ATTRACTION -> "eine Sehenswürdigkeit"
        PlaceKind.VIEWPOINT -> "ein Aussichtspunkt"
    }

    // The adjective would have to agree with the noun and its article.
    override fun withDenomination(denomination: String?, noun: String): String = noun

    override fun heritage(heritage: Heritage): String = when (heritage) {
        Heritage.WorldHeritage -> "Teil des UNESCO-Welterbes"
        Heritage.Rijksmonument -> "ein Rijksmonument, ein niederländisches Nationaldenkmal"
        is Heritage.ListedStatus, Heritage.Listed -> "ein geschütztes Baudenkmal"
        Heritage.National -> "ein nationales Denkmal"
    }

    override fun theOne(kind: PlaceKind, description: String): String? = null

    override fun formerName(name: String) = listOf("Früher hieß es $name.", "Einst war es als $name bekannt.")

    override fun dateLines(kind: PlaceKind, date: HistoricDate, ago: String): List<String> {
        val inDate = when (date.precision) {
            HistoricDate.Precision.YEAR -> "${date.year}"
            HistoricDate.Precision.DECADE -> "in den ${date.year / 10 * 10}er-Jahren"
            HistoricDate.Precision.CENTURY -> "im ${century(date)} Jahrhundert"
        }
        val from = when (date.precision) {
            HistoricDate.Precision.YEAR -> "aus dem Jahr ${date.year}"
            HistoricDate.Precision.DECADE -> "aus den ${date.year / 10 * 10}er-Jahren"
            HistoricDate.Precision.CENTURY -> "aus dem ${century(date)} Jahrhundert"
        }
        val since = when (date.precision) {
            HistoricDate.Precision.YEAR -> "seit ${date.year}"
            HistoricDate.Precision.DECADE -> "seit den ${date.year / 10 * 10}er-Jahren"
            HistoricDate.Precision.CENTURY -> "seit dem ${century(date)} Jahrhundert"
        }
        return when {
            kind.institution -> listOf("Gegründet wurde die Einrichtung $inDate$ago.", "Die Einrichtung stammt $from.")
            kind.isObject -> listOf("Aufgestellt wurde es $inDate$ago.", "Es steht hier $since.")
            else -> listOf("Erbaut wurde es $inDate$ago.", "Es stammt $from.", "Es steht hier $since.")
        }
    }

    /** "fünfzehnten", spelled out so every voice reads it right. */
    private fun century(date: HistoricDate): String = listOf(
        "ersten", "zweiten", "dritten", "vierten", "fünften", "sechsten", "siebten", "achten", "neunten", "zehnten",
        "elften", "zwölften", "dreizehnten", "vierzehnten", "fünfzehnten", "sechzehnten", "siebzehnten", "achtzehnten",
        "neunzehnten", "zwanzigsten", "einundzwanzigsten",
    ).getOrNull(date.century - 1) ?: "${date.century}."

    override fun ago(years: Int) = ", vor mehr als $years Jahren"

    override fun architectLines(kind: PlaceKind, names: String): List<String> = when {
        kind.isObject -> listOf("Es ist ein Werk von $names.", "Geschaffen hat es $names.")
        kind.institution -> listOf("Das Gebäude entwarf $names.", "Der Architekt des Gebäudes war $names.")
        else -> listOf("Entworfen hat es $names.", "Der Architekt war $names.")
    }

    override fun styleLines(style: String) = listOf("Achten Sie auf den Stil: $style.", "Der Baustil ist bekannt als $style.")

    override fun heritageLines(heritage: String) = listOf("Es ist $heritage.", "Heute gilt es als $heritage.")

    override fun namedAfterLines(name: String) = listOf("Benannt ist es nach $name.", "Der Name ehrt $name.")

    override fun occupantLines(name: String) = listOf("Heute ist hier $name untergebracht.", "Heute hat hier $name seinen Sitz.")

    override fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " und " + names.last()
    }
}
