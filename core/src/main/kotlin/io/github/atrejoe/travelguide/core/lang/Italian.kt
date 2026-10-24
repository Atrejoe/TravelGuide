package io.github.atrejoe.travelguide.core.lang

import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.RelativePosition
import io.github.atrejoe.travelguide.core.story.SectionKind
import kotlin.math.roundToInt

/**
 * Italian stories from the Italian Wikipedia (issue #68), addressing the listener as "Lei".
 * Phrases avoid agreeing with a place's gender: "La sua costruzione risale al 1488" rather than
 * "Fu costruito".
 */
object Italian : StoryLanguage("it", "Italian", "Italiano") {
    override val sectionRules = listOf(
        SectionKind.SKIP to listOf(
            "demografia", "popolazione", "società", "amministrazione", "politica", "economia", "trasporti",
            "infrastrutture", "istruzione", "sport", "gemellaggi", "note", "bibliografia", "collegamenti esterni",
            "voci correlate", "galleria", "persone legate", "media", "sanità", "statistiche", "frazioni", "quartieri",
            "elenco", "fonti", "altri progetti",
        ),
        SectionKind.NAME to listOf("toponimo", "etimologia", "nome"),
        SectionKind.LEGEND to listOf("leggenda", "folclore", "mito", "fantasm", "mistero"),
        SectionKind.HISTORY to listOf("storia", "origini", "fondazione", "medioevo", "medievale", "guerra", "antichità", "preistoria", "secolo"),
        SectionKind.CULTURE to listOf("cultura", "arte", "festa", "festival", "cucina", "gastronomia", "musica", "religione", "tradizion", "eventi"),
        SectionKind.LANDMARKS to listOf("monumenti", "architettur", "luoghi d'interesse", "patrimonio", "edifici", "attrazioni"),
        SectionKind.NATURE to listOf("geografia", "geologia", "natura", "paesaggio", "flora", "fauna", "ambiente", "corso", "idrografia", "territorio"),
        SectionKind.CLIMATE to listOf("clima", "meteo"),
    )

    override val abbreviations = setOf(
        "s", "ss", "st", "dott", "prof", "sig", "sig.ra", "ca", "ecc", "a.c", "d.c", "n", "nr", "km", "pag", "vol",
        "es", "cfr", "sec", "v",
    )
    override val sentenceStarters = setOf(
        "Il", "Lo", "La", "I", "Gli", "Le", "Un", "Una", "Uno", "In", "Questo", "Questa", "Questi", "Suo", "Sua", "Dopo",
        "Prima", "Durante", "Quando", "Dal", "Dalla", "Oggi", "Nel", "Nella", "Ma", "Con", "Per", "Da", "Di", "Del", "Della",
    )

    override val remarkable = Regex(
        "(?<!\\p{L})(più antic[oaih]+|più grandi?|più alt[oaie]|più lungh?[oaie]|più piccol[oaie]|prim[oaie]|unic[oaie]|" +
            "ultim[oaie]|famos[oaie]|celebri?|not[oa] come|soprannominat[oaie]|leggende?|leggendari[oaie]|miti?|mito|" +
            "secondo la|distrutt[oaie]|incendi[oa]?|alluvione|peste|assedio|assediat[oaie]|battaglie?|trattato|" +
            "scopert[oaie]|inventat[oaie]|sopravviss[oaie]|miracol[oi]|segret[oi]|mister[oi]|re|regina|imperatore|" +
            "roman[oaie]|vichinghi|medieval[ei]|monaci|monaco|cavalieri|sant[oa]|san|pellegrin[oaie]|tesor[oi]|rovin[ae]|ruder[ei])(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )
    override val dramatic = Regex(
        "(?<!\\p{L})(avvelen|intossicazione|massacro|strage|omicidio|assassin|giustiziat|decapitat|strega|catastrofe|disastro|esplosione|naufragio|terremoto|valanga|frana|epidemia|peste|carestia|rivolta|ribellione|insurrezione|ammutinamento|assedio|battaglia|rapina|rapiment|contrabband|scandalo|mister|infestat|fantasma|maledizione|miracolo|apparizione|tragedia)",
        RegexOption.IGNORE_CASE,
    )
    override val nameOrigin = Regex(
        "(?<!\\p{L})(deve il nome|deve il suo nome|prende il nome|chiamat[oa] in onore|deriva da|deriverebbe|proviene da|il nome|significa|significato)(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )
    override val dated = Regex("(?<!\\p{L})(1[0-9]{3}|20[0-2][0-9]|[XVI]+ secolo|[0-9]{1,2}° secolo|a\\.C|d\\.C)")
    override val administrative = Regex(
        "(?<!\\p{L})(abitanti|popolazione|censimento|comune di|km²|km2|chilometri quadrati|codice postale|densità|per cento|" +
            "famiglie|mediana|amministrativ|capoluogo|statistic|elett|consiglio comunale|sindaco|coordinate|" +
            "si trova nella provincia|è composto da)",
        RegexOption.IGNORE_CASE,
    )
    override val contextDependent = Regex("^(Esso|Essa|Essi|Esse|Questo|Questa|Questi|Queste|Suo|Sua|Suoi|Sue|Tuttavia|Ma|Anche|Entrambi|Lì|Inoltre)(?!\\p{L})")

    private val definitionVerbs = listOf(" è un", " è una", " è il", " è la", " è lo", " è l'", " era un", " era una", " sono ")

    override fun isDefinition(sentence: String, title: String): Boolean {
        val head = sentence.take(title.length + 40).lowercase()
        val name = title.lowercase()
        return sentence.length < 220 &&
            (head.startsWith(name) || listOf("il ", "lo ", "la ", "i ", "gli ", "le ", "l'", "l’").any { head.startsWith(it + name) }) &&
            definitionVerbs.any { it in head }
    }

    /** Italian descriptions ("comune italiano") don't say their gender; the article's first sentence does. */
    override fun identityLine(description: String): String? = null

    override fun kindLine(kind: PlaceKind) = "È ${placeNoun(kind)}."

    override fun leads(kind: SectionKind): List<String> = when (kind) {
        SectionKind.NAME -> listOf("Si è mai chiesto da dove viene il nome?", "Il nome ha una sua storia.")
        SectionKind.LEGEND -> listOf("C'è anche una leggenda.", "E poi c'è la storia che si racconta.")
        SectionKind.HISTORY -> listOf("Un po' di storia.", "Torniamo indietro nel tempo.", "La storia ha lasciato qui il suo segno.")
        SectionKind.CULTURE -> listOf("Qui la cultura ha radici profonde.", "Qualcosa sulla vita locale.")
        SectionKind.LANDMARKS -> listOf("Guardi bene e noterà questo.", "Un dettaglio che vale la pena conoscere.")
        SectionKind.NATURE -> listOf("Ora, il paesaggio.", "La natura qui ha una sua storia.")
        SectionKind.CLIMATE -> listOf("Quanto al clima.")
        else -> emptyList()
    }

    override val interestKeywords = mapOf(
        Interest.HISTORY to listOf(
            "battaglia", "castello", "forte", "guerra", "antic", "medieval", "roman", "secolo", "storic", "rovin",
            "monumento", "memoriale", "archeolog", "abbazia", "palazzo", "fondat", "assedio", "monastero", "tenuta",
            "dinastia", "vichingh", "preistor", "sepoltura", "tumulo", "fortezza", "bastione", "cittadella", "patrimonio",
            "demolit", "ricostruit",
        ),
        Interest.NATURE to listOf(
            "fiume", "lago", "montagna", "monte", "bosco", "foresta", "riserva naturale", "parco nazionale", "valle",
            "collina", "isola", "ghiacciaio", "duna", "brughiera", "palude", "uccell", "specie", "clima", "geolog",
        ),
        Interest.CULTURE to listOf(
            "museo", "festival", "teatro", "arte", "musica", "pittore", "scrittore", "cucina", "tradizion", "religi",
            "chiesa", "cattedrale", "duomo", "moschea", "tempio", "università",
        ),
        Interest.ARCHITECTURE to listOf(
            "edificio", "torre", "ponte", "architett", "mulino", "casa", "castello", "chiesa", "cattedrale", "duomo",
            "stazione", "canale", "chiusa", "diga", "grattacielo",
        ),
    )
    override val placeNouns = setOf(
        "città", "paese", "comune", "frazione", "località", "capoluogo", "capitale", "provincia", "regione", "contea",
        "distretto", "quartiere", "borgo", "villaggio", "ex", "antica",
    )
    override val notPlaceNouns = setOf("chiesa", "castello", "forte", "mulino")

    override fun hereOpeners(title: String, area: Boolean, flying: Boolean): List<String> = when {
        area && flying -> listOf("Sotto di noi si stende ora $title.", "Stiamo sorvolando $title.")
        area -> listOf(
            "Benvenuti a $title.",
            "Siamo appena arrivati a $title.",
            "Ora attraversiamo $title.",
            "Eccoci, a $title.",
        )
        else -> listOf(
            "Si guardi intorno: è proprio accanto a $title.",
            "Proprio qui si trova $title.",
            "Si fermi un momento. Questo è $title.",
        )
    }

    // A distance comes with its own "circa", so "a $distance" reads "a circa 800 metri" or "a un chilometro".
    override fun aheadOpeners(title: String, distance: String) = listOf(
        "Più avanti, a $distance, si trova $title.",
        "Tenga d'occhio la strada: a $distance da qui c'è $title.",
        "Poco più avanti, a $distance da qui, c'è $title.",
    )

    override fun sideOpeners(title: String, left: Boolean, distance: String, window: Boolean): List<String> {
        val side = if (left) "sinistra" else "destra"
        val sideAdjective = if (left) "sinistro" else "destro"
        return if (window) {
            listOf(
                "Guardi dal finestrino di $side. A $distance si trova $title.",
                "Se è seduto sul lato $sideAdjective, guardi ora: a $distance c'è $title.",
            )
        } else {
            listOf(
                "Guardi alla sua $side. A $distance c'è $title.",
                "Sulla sua $side, a $distance, si trova $title.",
                "Verso $side, a $distance da qui, troverà $title.",
            )
        }
    }

    override fun behindOpeners(title: String, distance: String) =
        listOf("Proprio dietro di noi, a $distance, c'è $title.", "Abbiamo appena superato $title, a $distance dietro di noi.")

    override fun compassOpeners(title: String, direction: String, distance: String) = listOf(
        "A $distance verso $direction si trova $title.",
        "Non lontano da qui, a $distance verso $direction, c'è $title.",
    )

    override fun afieldOpeners(title: String, relative: RelativePosition): List<String> = when (relative) {
        RelativePosition.Here -> listOf("Tutto intorno a noi si stende $title.", "La zona in cui ci troviamo è $title.")
        is RelativePosition.Ahead -> {
            val d = distance(relative.distanceM)
            listOf("Più avanti lungo il percorso, a $d, si trova $title.", "Ancora fuori vista, a $d più avanti, c'è $title.")
        }
        is RelativePosition.Side -> {
            val d = distance(relative.distanceM)
            val side = if (relative.left) "sinistra" else "destra"
            listOf("Un po' più lontano, a $d alla sua $side, si trova $title.", "Fuori vista, a $d verso $side, c'è $title.")
        }
        is RelativePosition.Behind -> {
            val d = distance(relative.distanceM)
            listOf("A $d dietro di noi si trova $title.", "Tornando sui nostri passi, a $d, c'è $title.")
        }
        is RelativePosition.Compass -> {
            val d = distance(relative.distanceM)
            val direction = compassName(relative.bearingDeg)
            listOf("Un po' più lontano, a $d verso $direction, si trova $title.", "Fuori vista, a $d verso $direction, c'è $title.")
        }
    }

    override fun transitions(title: String, where: String, mentioned: Boolean): List<String> = if (mentioned) {
        listOf("Il che ci porta a $title, $where.", "E la storia continua a $title, $where.")
    } else {
        listOf(
            "Poco più in là, $where, troverà $title.",
            "Non lontano da lì, $where, si trova $title.",
            "E visto che ci siamo: $where c'è $title.",
        )
    }

    override fun closings(relative: RelativePosition, mode: TravelMode): List<String> = when {
        relative is RelativePosition.Behind -> emptyList()
        mode == TravelMode.WALKING || mode == TravelMode.STATIONARY ->
            listOf("Si prenda un momento per guardarsi intorno.", "Merita uno sguardo più da vicino, se ha tempo.")
        mode == TravelMode.CYCLING -> listOf("Merita una sosta, se le gambe lo permettono.", "Qualcosa da notare mentre pedala.")
        relative is RelativePosition.Here -> listOf("Tenga gli occhi aperti mentre lo attraversiamo.")
        else -> listOf("Tenga gli occhi aperti mentre passiamo.", "Qualcosa da ricordare per una prossima visita.")
    }

    override fun where(relative: RelativePosition): String = when (relative) {
        RelativePosition.Here -> "proprio qui"
        is RelativePosition.Ahead -> "proprio davanti, a ${distance(relative.distanceM)}"
        is RelativePosition.Side -> "alla sua ${if (relative.left) "sinistra" else "destra"}, a ${distance(relative.distanceM)}"
        is RelativePosition.Behind -> "proprio dietro di noi"
        is RelativePosition.Compass -> "a ${distance(relative.distanceM)} verso ${compassName(relative.bearingDeg)}"
    }

    override fun distance(meters: Double): String = when {
        meters < 950 -> "circa ${((meters / 100).roundToInt() * 100).coerceAtLeast(100)} metri"
        meters < 1_050 -> "un chilometro"
        meters < 9_950 -> {
            val km = (meters / 100).roundToInt() / 10.0
            val text = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString().replace('.', ',')
            "circa $text chilometri"
        }
        else -> "circa ${(meters / 1000).roundToInt()} chilometri"
    }

    override val compassNames = listOf("nord", "nord-est", "est", "sud-est", "sud", "sud-ovest", "ovest", "nord-ovest")

    /** With its indefinite article, which depends on the noun's gender. */
    override fun placeNoun(kind: PlaceKind): String = when (kind) {
        PlaceKind.TOWN_HALL -> "un municipio"
        PlaceKind.CATHEDRAL -> "una cattedrale"
        PlaceKind.CHURCH -> "una chiesa"
        PlaceKind.CHAPEL -> "una cappella"
        PlaceKind.MOSQUE -> "una moschea"
        PlaceKind.SYNAGOGUE -> "una sinagoga"
        PlaceKind.TEMPLE -> "un tempio"
        PlaceKind.MONASTERY -> "un monastero"
        PlaceKind.MUSEUM -> "un museo"
        PlaceKind.GALLERY -> "una galleria d'arte"
        PlaceKind.LIBRARY -> "una biblioteca"
        PlaceKind.UNIVERSITY -> "un edificio universitario"
        PlaceKind.COLLEGE -> "un istituto superiore"
        PlaceKind.COURTHOUSE -> "un palazzo di giustizia"
        PlaceKind.THEATRE -> "un teatro"
        PlaceKind.CONCERT_HALL -> "una sala da concerto"
        PlaceKind.ARTS_CENTRE -> "un centro culturale"
        PlaceKind.STATION -> "una stazione ferroviaria"
        PlaceKind.CASTLE -> "un castello"
        PlaceKind.PALACE -> "un palazzo"
        PlaceKind.FORT -> "una fortezza"
        PlaceKind.CITY_GATE -> "una porta cittadina"
        PlaceKind.CITY_WALL -> "una cinta muraria"
        PlaceKind.TOWER -> "una torre"
        PlaceKind.WINDMILL -> "un mulino a vento"
        PlaceKind.WATERMILL -> "un mulino ad acqua"
        PlaceKind.LIGHTHOUSE -> "un faro"
        PlaceKind.MANOR -> "una villa padronale"
        PlaceKind.RUINS -> "un rudere"
        PlaceKind.ARCHAEOLOGICAL_SITE -> "un sito archeologico"
        PlaceKind.MONUMENT -> "un monumento"
        PlaceKind.MEMORIAL -> "un memoriale"
        PlaceKind.STATUE -> "una statua"
        PlaceKind.ARTWORK -> "un'opera d'arte"
        PlaceKind.FOUNTAIN -> "una fontana"
        PlaceKind.SHIP -> "una nave storica"
        PlaceKind.HISTORIC_BUILDING -> "un edificio storico"
        PlaceKind.BUILDING -> "un edificio"
        PlaceKind.ATTRACTION -> "un luogo d'interesse"
        PlaceKind.VIEWPOINT -> "un belvedere"
    }

    override fun withDenomination(denomination: String?, noun: String): String = noun

    override fun heritage(heritage: Heritage): String = when (heritage) {
        Heritage.WorldHeritage -> "parte di un sito Patrimonio dell'umanità dell'UNESCO"
        Heritage.Rijksmonument -> "un rijksmonument, un monumento nazionale olandese"
        is Heritage.ListedStatus, Heritage.Listed -> "un monumento tutelato"
        Heritage.National -> "un monumento nazionale"
    }

    override fun theOne(kind: PlaceKind, description: String): String? = null

    override fun formerName(name: String) = listOf("Un tempo si chiamava $name.", "Il suo antico nome è $name.")

    override fun dateLines(kind: PlaceKind, date: HistoricDate, ago: String): List<String> {
        val decade = date.year / 10 * 10
        val to = when (date.precision) {
            HistoricDate.Precision.YEAR -> "al ${date.year}"
            HistoricDate.Precision.DECADE -> "agli anni $decade"
            HistoricDate.Precision.CENTURY -> "al ${century(date)} secolo"
        }
        val since = when (date.precision) {
            HistoricDate.Precision.YEAR -> "dal ${date.year}"
            HistoricDate.Precision.DECADE -> "dagli anni $decade"
            HistoricDate.Precision.CENTURY -> "dal ${century(date)} secolo"
        }
        return when {
            kind.institution -> listOf("La sua fondazione risale $to$ago.", "Le sue origini risalgono $to.")
            kind.isObject -> listOf("La sua collocazione qui risale $to$ago.", "Si trova qui $since.")
            else -> listOf("La sua costruzione risale $to$ago.", "Risale $to.", "Sorge qui $since.")
        }
    }

    /** "quindicesimo", spelled out so every voice reads it right. */
    private fun century(date: HistoricDate): String = listOf(
        "primo", "secondo", "terzo", "quarto", "quinto", "sesto", "settimo", "ottavo", "nono", "decimo", "undicesimo",
        "dodicesimo", "tredicesimo", "quattordicesimo", "quindicesimo", "sedicesimo", "diciassettesimo", "diciottesimo",
        "diciannovesimo", "ventesimo", "ventunesimo",
    ).getOrNull(date.century - 1) ?: "${date.century}°"

    override fun ago(years: Int) = ", più di $years anni fa"

    override fun architectLines(kind: PlaceKind, names: String): List<String> = when {
        kind.isObject -> listOf("È opera di $names.", "L'autore è $names.")
        kind.institution -> listOf("L'edificio fu progettato da $names.", "L'architetto dell'edificio fu $names.")
        else -> listOf("Il progetto è di $names.", "L'architetto fu $names.")
    }

    override fun styleLines(style: String) = listOf("Noti il suo stile: $style.", "Il suo stile è noto come $style.")

    override fun heritageLines(heritage: String) = listOf("È $heritage.", "Oggi è $heritage.")

    override fun namedAfterLines(name: String) = listOf("Il nome rende omaggio a $name.", "Deve il suo nome a $name.")

    override fun occupantLines(name: String) = listOf("Oggi ospita $name.", "Attualmente vi ha sede $name.")

    override fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " e " + names.last()
    }
}
