package io.github.atrejoe.travelguide.core.lang

import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.RelativePosition
import io.github.atrejoe.travelguide.core.story.SectionKind
import kotlin.math.roundToInt

/**
 * Spanish stories from the Spanish Wikipedia (issue #68), addressing the listener as "usted".
 * Phrases avoid agreeing with a place's gender: "Se construyó en 1488" rather than "Fue construido".
 */
object Spanish : StoryLanguage("es", "Spanish", "Español") {
    override val sectionRules = listOf(
        SectionKind.SKIP to listOf(
            "demografía", "población", "gobierno", "política", "administración", "economía", "transporte", "educación",
            "deporte", "hermanad", "hermanamiento", "referencias", "notas", "bibliografía", "enlaces externos",
            "véase también", "galería", "personajes", "personas", "infraestructura", "medios", "salud", "estadística",
            "barrios", "lista", "fuentes",
        ),
        SectionKind.NAME to listOf("toponimia", "etimología", "nombre"),
        SectionKind.LEGEND to listOf("leyenda", "folclore", "mito", "fantasma", "misterio"),
        SectionKind.HISTORY to listOf("historia", "origen", "fundación", "edad media", "medieval", "guerra", "antigüedad", "prehistoria", "siglo"),
        SectionKind.CULTURE to listOf("cultura", "arte", "fiesta", "festival", "gastronomía", "cocina", "música", "religión", "tradición", "evento"),
        SectionKind.LANDMARKS to listOf("monumento", "arquitectura", "lugares de interés", "patrimonio", "edificio", "atracción"),
        SectionKind.NATURE to listOf("geografía", "geología", "naturaleza", "paisaje", "flora", "fauna", "medio ambiente", "curso", "hidrografía", "ubicación", "situación"),
        SectionKind.CLIMATE to listOf("clima", "tiempo"),
    )

    override val abbreviations = setOf(
        "st", "sto", "sta", "dr", "dra", "sr", "sra", "srta", "d", "dña", "ca", "aprox", "etc", "a.c", "d.c", "n.º",
        "núm", "no", "km", "pág", "vol", "ej", "p.ej", "s", "av", "avda",
    )
    override val sentenceStarters = setOf(
        "El", "La", "Los", "Las", "Un", "Una", "En", "Este", "Esta", "Estos", "Su", "Sus", "Tras", "Antes", "Durante",
        "Cuando", "Desde", "Hoy", "Sin", "Pero", "Allí", "Con", "Por", "De", "Del", "Se",
    )

    override val remarkable = Regex(
        "(?<!\\p{L})(más antigu[oa]s?|más grande|mayor|más alt[oa]s?|más larg[oa]s?|más pequeñ[oa]s?|primer[oa]?|únic[oa]s?|" +
            "últim[oa]s?|famos[oa]s?|célebres?|notables?|legendari[oa]s?|leyendas?|mitos?|según|apodad[oa]|" +
            "conocid[oa] como|destruid[oa]s?|incendios?|incendiad[oa]|inundación|peste|asedio|sitio de|batallas?|tratado|" +
            "descubiert[oa]|inventad[oa]|sobrevivi(ó|eron)|milagros?|secretos?|misterios?|rey|reina|emperador|" +
            "roman[oa]s?|vikingos?|medieval(es)?|monjes?|caballeros?|sant[oa]|san|peregrin[oa]s?|tesoros?|ruinas?)(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )
    override val dramatic = Regex(
        "(?<!\\p{L})(envenen|intoxicación|masacre|matanza|asesin|ejecutad|decapitad|bruja|catástrofe|desastre|explosión|naufragio|terremoto|avalancha|epidemia|peste|hambruna|motín|revuelta|rebelión|levantamiento|asedio|batalla|atraco|secuestr|contrabando|escándalo|misterio|embrujad|fantasma|maldición|milagro|aparición|tragedia)",
        RegexOption.IGNORE_CASE,
    )
    override val nameOrigin = Regex(
        "(?<!\\p{L})(debe su nombre|toma su nombre|nombrad[oa] en honor|deriva de|procede de|proviene de|el nombre|significa|significado)(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )
    override val dated = Regex("(?<!\\p{L})(1[0-9]{3}|20[0-2][0-9]|siglo [XVI]+|siglo [0-9]{1,2}|a\\. ?C|d\\. ?C)")
    override val administrative = Regex(
        "(?<!\\p{L})(habitantes|población|censo|municipio de|km²|km2|kilómetros cuadrados|código postal|densidad|por ciento|" +
            "hogares|mediana|administrativ|estadístic|elegid|alcalde|coordenadas|se encuentra en la provincia|está formado por)",
        RegexOption.IGNORE_CASE,
    )
    override val contextDependent = Regex("^(Él|Ella|Ellos|Ellas|Este|Esta|Estos|Estas|Su|Sus|Sin embargo|Pero|También|Ambos|Allí|Además)(?!\\p{L})")

    private val definitionVerbs = listOf(" es un", " es una", " es el", " es la", " era un", " era una", " son ")

    override fun isDefinition(sentence: String, title: String): Boolean {
        val head = sentence.take(title.length + 40).lowercase()
        val name = title.lowercase()
        return sentence.length < 220 &&
            (head.startsWith(name) || listOf("el ", "la ", "los ", "las ").any { head.startsWith(it + name) }) &&
            definitionVerbs.any { it in head }
    }

    /** Spanish descriptions ("municipio de España") don't say their gender; the article's first sentence does. */
    override fun identityLine(description: String): String? = null

    override fun kindLine(kind: PlaceKind) = "Es ${placeNoun(kind)}."

    override fun leads(kind: SectionKind): List<String> = when (kind) {
        SectionKind.NAME -> listOf("¿Se ha preguntado de dónde viene el nombre?", "El nombre tiene su propia historia.")
        SectionKind.LEGEND -> listOf("También hay una leyenda.", "Y luego está la historia que cuenta la gente.")
        SectionKind.HISTORY -> listOf("Un poco de historia.", "Viajemos atrás en el tiempo.", "La historia ha dejado aquí su huella.")
        SectionKind.CULTURE -> listOf("La cultura tiene aquí raíces profundas.", "Algo sobre la vida local.")
        SectionKind.LANDMARKS -> listOf("Fíjese bien y notará esto.", "Un detalle que vale la pena conocer.")
        SectionKind.NATURE -> listOf("Ahora, sobre el paisaje.", "La naturaleza tiene aquí su propia historia.")
        SectionKind.CLIMATE -> listOf("En cuanto al clima.")
        else -> emptyList()
    }

    override val interestKeywords = mapOf(
        Interest.HISTORY to listOf(
            "batalla", "castillo", "fuerte", "guerra", "antigu", "medieval", "romano", "siglo", "históric", "ruina",
            "monumento", "memorial", "arqueológ", "abadía", "palacio", "fundad", "asedio", "monasterio", "mansión",
            "dinastía", "vikingo", "prehistór", "sepultura", "túmulo", "fortaleza", "muralla", "ciudadela", "patrimonio",
            "demolid", "reconstruid",
        ),
        Interest.NATURE to listOf(
            "río", "lago", "montaña", "bosque", "reserva natural", "parque nacional", "valle", "colina", "isla",
            "glaciar", "duna", "brezal", "humedal", "especie", "clima", "geológ",
        ),
        Interest.CULTURE to listOf(
            "museo", "festival", "teatro", "arte", "música", "pintor", "escritor", "gastronomía", "tradición", "religi",
            "iglesia", "catedral", "mezquita", "templo", "universidad",
        ),
        Interest.ARCHITECTURE to listOf(
            "edificio", "torre", "puente", "arquitect", "molino", "casa", "castillo", "iglesia", "catedral", "estación",
            "canal", "esclusa", "presa", "rascacielos",
        ),
    )
    override val placeNouns = setOf(
        "ciudad", "pueblo", "municipio", "localidad", "capital", "provincia", "región", "comarca", "condado", "distrito",
        "barrio", "aldea", "parroquia", "antigua", "lugar", "villa",
    )
    override val notPlaceNouns = setOf("iglesia", "castillo", "fuerte", "molino")

    override fun hereOpeners(title: String, area: Boolean, flying: Boolean): List<String> = when {
        area && flying -> listOf("Bajo nosotros se extiende ahora $title.", "Estamos sobrevolando $title.")
        area -> listOf(
            "Bienvenido a $title.",
            "Acabamos de llegar a $title.",
            "Ahora atraviesa $title.",
            "Y aquí estamos, en $title.",
        )
        else -> listOf(
            "Mire a su alrededor: está justo al lado de $title.",
            "Justo aquí se encuentra $title.",
            "Deténgase un momento. Esto es $title.",
        )
    }

    // A distance comes with its own "unos", so "a $distance" reads "a unos 800 metros" or "a un kilómetro".
    override fun aheadOpeners(title: String, distance: String) = listOf(
        "Más adelante, a $distance, se encuentra $title.",
        "No pierda de vista la carretera: a $distance de aquí está $title.",
        "Justo delante, a $distance de aquí, está $title.",
    )

    override fun sideOpeners(title: String, left: Boolean, distance: String, window: Boolean): List<String> {
        val side = if (left) "izquierda" else "derecha"
        val sideAdjective = if (left) "izquierdo" else "derecho"
        return if (window) {
            listOf(
                "Mire por la ventanilla de la $side. A $distance se encuentra $title.",
                "Si va sentado en el lado $sideAdjective, mire ahora: a $distance está $title.",
            )
        } else {
            listOf(
                "Mire a su $side. A $distance se encuentra $title.",
                "A su $side, a $distance, está $title.",
                "Hacia la $side, a $distance de aquí, encontrará $title.",
            )
        }
    }

    override fun behindOpeners(title: String, distance: String) =
        listOf("Justo detrás de nosotros, a $distance, está $title.", "Acabamos de pasar $title, a $distance detrás de nosotros.")

    override fun compassOpeners(title: String, direction: String, distance: String) = listOf(
        "A $distance hacia el $direction se encuentra $title.",
        "No lejos de aquí, a $distance hacia el $direction, está $title.",
    )

    override fun afieldOpeners(title: String, relative: RelativePosition): List<String> = when (relative) {
        RelativePosition.Here -> listOf("A nuestro alrededor se extiende $title.", "La zona en la que estamos es $title.")
        is RelativePosition.Ahead -> {
            val d = distance(relative.distanceM)
            listOf("Más adelante en el camino, a $d, se encuentra $title.", "Todavía fuera de la vista, a $d por delante, está $title.")
        }
        is RelativePosition.Side -> {
            val d = distance(relative.distanceM)
            val side = if (relative.left) "izquierda" else "derecha"
            listOf("Un poco más lejos, a $d a su $side, se encuentra $title.", "Fuera de la vista, a $d hacia la $side, está $title.")
        }
        is RelativePosition.Behind -> {
            val d = distance(relative.distanceM)
            listOf("A $d detrás de nosotros se encuentra $title.", "Volviendo por donde vinimos, a $d, está $title.")
        }
        is RelativePosition.Compass -> {
            val d = distance(relative.distanceM)
            val direction = compassName(relative.bearingDeg)
            listOf("Un poco más lejos, a $d hacia el $direction, se encuentra $title.", "Fuera de la vista, a $d hacia el $direction, está $title.")
        }
    }

    override fun transitions(title: String, where: String, mentioned: Boolean): List<String> = if (mentioned) {
        listOf("Lo que nos lleva a $title, $where.", "Y la historia continúa en $title, $where.")
    } else {
        listOf(
            "Un poco más allá, $where, encontrará $title.",
            "No lejos de allí, $where, se encuentra $title.",
            "Y ya que estamos aquí: $where está $title.",
        )
    }

    override fun closings(relative: RelativePosition, mode: TravelMode): List<String> = when {
        relative is RelativePosition.Behind -> emptyList()
        mode == TravelMode.WALKING || mode == TravelMode.STATIONARY ->
            listOf("Tómese un momento para mirar a su alrededor.", "Merece una mirada más de cerca, si tiene tiempo.")
        mode == TravelMode.CYCLING -> listOf("Merece una parada, si las piernas lo permiten.", "Algo en lo que fijarse mientras pedalea.")
        relative is RelativePosition.Here -> listOf("Mantenga los ojos abiertos mientras lo atravesamos.")
        else -> listOf("Esté atento al pasar.", "Algo para recordar en una próxima visita.")
    }

    override fun where(relative: RelativePosition): String = when (relative) {
        RelativePosition.Here -> "justo aquí"
        is RelativePosition.Ahead -> "justo delante, a ${distance(relative.distanceM)}"
        is RelativePosition.Side -> "a su ${if (relative.left) "izquierda" else "derecha"}, a ${distance(relative.distanceM)}"
        is RelativePosition.Behind -> "justo detrás de nosotros"
        is RelativePosition.Compass -> "a ${distance(relative.distanceM)} hacia el ${compassName(relative.bearingDeg)}"
    }

    override fun distance(meters: Double): String = when {
        meters < 950 -> "unos ${((meters / 100).roundToInt() * 100).coerceAtLeast(100)} metros"
        meters < 1_050 -> "un kilómetro"
        meters < 9_950 -> {
            val km = (meters / 100).roundToInt() / 10.0
            val text = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString().replace('.', ',')
            "unos $text kilómetros"
        }
        else -> "unos ${(meters / 1000).roundToInt()} kilómetros"
    }

    override val compassNames = listOf("norte", "noreste", "este", "sureste", "sur", "suroeste", "oeste", "noroeste")

    /** With its indefinite article, which depends on the noun's gender. */
    override fun placeNoun(kind: PlaceKind): String = when (kind) {
        PlaceKind.TOWN_HALL -> "un ayuntamiento"
        PlaceKind.CATHEDRAL -> "una catedral"
        PlaceKind.CHURCH -> "una iglesia"
        PlaceKind.CHAPEL -> "una capilla"
        PlaceKind.MOSQUE -> "una mezquita"
        PlaceKind.SYNAGOGUE -> "una sinagoga"
        PlaceKind.TEMPLE -> "un templo"
        PlaceKind.MONASTERY -> "un monasterio"
        PlaceKind.MUSEUM -> "un museo"
        PlaceKind.GALLERY -> "una galería de arte"
        PlaceKind.LIBRARY -> "una biblioteca"
        PlaceKind.UNIVERSITY -> "un edificio universitario"
        PlaceKind.COLLEGE -> "una escuela superior"
        PlaceKind.COURTHOUSE -> "un palacio de justicia"
        PlaceKind.THEATRE -> "un teatro"
        PlaceKind.CONCERT_HALL -> "una sala de conciertos"
        PlaceKind.ARTS_CENTRE -> "un centro cultural"
        PlaceKind.STATION -> "una estación de tren"
        PlaceKind.CASTLE -> "un castillo"
        PlaceKind.PALACE -> "un palacio"
        PlaceKind.FORT -> "una fortaleza"
        PlaceKind.CITY_GATE -> "una puerta de la ciudad"
        PlaceKind.CITY_WALL -> "una muralla"
        PlaceKind.TOWER -> "una torre"
        PlaceKind.WINDMILL -> "un molino de viento"
        PlaceKind.WATERMILL -> "un molino de agua"
        PlaceKind.LIGHTHOUSE -> "un faro"
        PlaceKind.MANOR -> "una casa señorial"
        PlaceKind.RUINS -> "una ruina"
        PlaceKind.ARCHAEOLOGICAL_SITE -> "un yacimiento arqueológico"
        PlaceKind.MONUMENT -> "un monumento"
        PlaceKind.MEMORIAL -> "un memorial"
        PlaceKind.STATUE -> "una estatua"
        PlaceKind.ARTWORK -> "una obra de arte"
        PlaceKind.FOUNTAIN -> "una fuente"
        PlaceKind.SHIP -> "un barco histórico"
        PlaceKind.HISTORIC_BUILDING -> "un edificio histórico"
        PlaceKind.BUILDING -> "un edificio"
        PlaceKind.ATTRACTION -> "un lugar de interés"
        PlaceKind.VIEWPOINT -> "un mirador"
    }

    override fun withDenomination(denomination: String?, noun: String): String = noun

    override fun heritage(heritage: Heritage): String = when (heritage) {
        Heritage.WorldHeritage -> "parte de un sitio Patrimonio de la Humanidad de la UNESCO"
        Heritage.Rijksmonument -> "un rijksmonument, un monumento nacional neerlandés"
        is Heritage.ListedStatus, Heritage.Listed -> "un monumento protegido"
        Heritage.National -> "un monumento nacional"
    }

    override fun theOne(kind: PlaceKind, description: String): String? = null

    override fun formerName(name: String) = listOf("Antes se llamaba $name.", "Antiguamente se conocía como $name.")

    override fun dateLines(kind: PlaceKind, date: HistoricDate, ago: String): List<String> {
        val decade = date.year / 10 * 10
        val inDate = when (date.precision) {
            HistoricDate.Precision.YEAR -> "en ${date.year}"
            HistoricDate.Precision.DECADE -> "en la década de $decade"
            HistoricDate.Precision.CENTURY -> "en el siglo ${century(date)}"
        }
        val from = when (date.precision) {
            HistoricDate.Precision.YEAR -> "de ${date.year}"
            HistoricDate.Precision.DECADE -> "de la década de $decade"
            HistoricDate.Precision.CENTURY -> "del siglo ${century(date)}"
        }
        val since = when (date.precision) {
            HistoricDate.Precision.YEAR -> "desde ${date.year}"
            HistoricDate.Precision.DECADE -> "desde la década de $decade"
            HistoricDate.Precision.CENTURY -> "desde el siglo ${century(date)}"
        }
        return when {
            kind.institution -> listOf("Se fundó $inDate$ago.", "Su fundación data $from.")
            kind.isObject -> listOf("Se colocó aquí $inDate$ago.", "Está aquí $since.")
            else -> listOf("Se construyó $inDate$ago.", "Data $from.", "Se levanta aquí $since.")
        }
    }

    /** "quince": Spanish reads centuries as cardinals, spelled out so every voice reads them right. */
    private fun century(date: HistoricDate): String = listOf(
        "primero", "segundo", "tercero", "cuarto", "quinto", "sexto", "séptimo", "octavo", "noveno", "décimo", "once",
        "doce", "trece", "catorce", "quince", "dieciséis", "diecisiete", "dieciocho", "diecinueve", "veinte", "veintiuno",
    ).getOrNull(date.century - 1) ?: "${date.century}"

    override fun ago(years: Int) = ", hace más de $years años"

    override fun architectLines(kind: PlaceKind, names: String): List<String> = when {
        kind.isObject -> listOf("Es obra de $names.", "Su autor es $names.")
        kind.institution -> listOf("El edificio es obra de $names.", "El arquitecto del edificio fue $names.")
        else -> listOf("Su diseño es obra de $names.", "El arquitecto fue $names.")
    }

    override fun styleLines(style: String) = listOf("Fíjese en su estilo: $style.", "Su estilo se conoce como $style.")

    override fun heritageLines(heritage: String) = listOf("Es $heritage.", "Hoy es $heritage.")

    override fun namedAfterLines(name: String) = listOf("Su nombre rinde homenaje a $name.", "Debe su nombre a $name.")

    override fun occupantLines(name: String) = listOf("Hoy alberga $name.", "Actualmente acoge $name.")

    override fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " y " + names.last()
    }
}
