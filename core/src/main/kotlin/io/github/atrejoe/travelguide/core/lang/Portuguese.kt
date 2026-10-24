package io.github.atrejoe.travelguide.core.lang

import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.RelativePosition
import io.github.atrejoe.travelguide.core.story.SectionKind
import kotlin.math.roundToInt

/**
 * Portuguese stories from the Portuguese Wikipedia (issue #68), in wording that works in both
 * Portugal and Brazil. Phrases avoid agreeing with a place's gender: "A sua construção data de
 * 1488" rather than "Foi construído".
 */
object Portuguese : StoryLanguage("pt", "Portuguese", "Português") {
    override val sectionRules = listOf(
        SectionKind.SKIP to listOf(
            "demografia", "população", "governo", "política", "administração", "economia", "transporte", "educação",
            "desporto", "esporte", "geminaç", "cidades-irmãs", "referências", "notas", "bibliografia", "ligações externas",
            "links externos", "ver também", "galeria", "personalidades", "infraestrutura", "mídia", "saúde",
            "estatística", "freguesias", "bairros", "lista", "fontes",
        ),
        SectionKind.NAME to listOf("toponímia", "etimologia", "nome"),
        SectionKind.LEGEND to listOf("lenda", "folclore", "mito", "fantasma", "mistério"),
        SectionKind.HISTORY to listOf("história", "origem", "fundação", "idade média", "medieval", "guerra", "antiguidade", "pré-história", "século"),
        SectionKind.CULTURE to listOf("cultura", "arte", "festa", "festival", "culinária", "gastronomia", "música", "religião", "tradição", "evento"),
        SectionKind.LANDMARKS to listOf("monumento", "arquitetura", "pontos de interesse", "património", "patrimônio", "edifício", "atração"),
        SectionKind.NATURE to listOf("geografia", "geologia", "natureza", "paisagem", "flora", "fauna", "ambiente", "curso", "hidrografia", "localização"),
        SectionKind.CLIMATE to listOf("clima", "tempo"),
    )

    override val abbreviations = setOf(
        "s", "sto", "sta", "dr", "dra", "sr", "sra", "prof", "ca", "aprox", "etc", "a.c", "d.c", "n.º", "nº", "km", "pág",
        "vol", "ex", "av",
    )
    override val sentenceStarters = setOf(
        "O", "A", "Os", "As", "Um", "Uma", "Em", "No", "Na", "Este", "Esta", "Seu", "Sua", "Após", "Antes", "Durante",
        "Quando", "Desde", "Hoje", "Mas", "Com", "Por", "De", "Do", "Da", "Ele", "Ela",
    )

    override val remarkable = Regex(
        "(?<!\\p{L})(mais antig[oa]s?|maior(es)?|mais alt[oa]s?|mais long[oa]s?|mais pequen[oa]s?|menor|primeir[oa]s?|" +
            "únic[oa]s?|últim[oa]s?|famos[oa]s?|célebres?|notáve(l|is)|conhecid[oa] como|apelidad[oa]|lendas?|" +
            "lendári[oa]s?|mitos?|segundo a|destruíd[oa]s?|incêndios?|incendiad[oa]|inundaç(ão|ões)|cheias?|peste|cerco|" +
            "sitiad[oa]|batalhas?|tratado|descobert[oa]|inventad[oa]|sobreviveu|milagres?|segredos?|mistérios?|rei|rainha|" +
            "imperador|roman[oa]s?|vikings?|medieva(l|is)|monges?|cavaleiros?|sant[oa]|são|peregrin[oa]s?|tesouros?|ruínas?)(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )
    override val dramatic = Regex(
        "(?<!\\p{L})(envenen|intoxicação|massacre|chacina|assassin|executad|decapitad|bruxa|catástrofe|desastre|explosão|naufrágio|terramoto|terremoto|avalanche|epidemia|peste|motim|revolta|rebelião|cerco|batalha|assalto|sequestr|contrabando|escândalo|mistério|misterios|assombrad|fantasma|maldição|milagre|aparição|tragédia)",
        RegexOption.IGNORE_CASE,
    )
    override val nameOrigin = Regex(
        "(?<!\\p{L})(deve o nome|deve o seu nome|nomead[oa] em homenagem|deriva de|provém de|vem de|o nome|significa|significado)(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )
    override val dated = Regex("(?<!\\p{L})(1[0-9]{3}|20[0-2][0-9]|século [XVI]+|a\\. ?C|d\\. ?C)")
    override val administrative = Regex(
        "(?<!\\p{L})(habitantes|população|censo|município de|km²|km2|quilómetros quadrados|quilômetros quadrados|código postal|" +
            "densidade|por cento|agregados|mediana|administrativ|sede de|estatístic|eleit|presidente da câmara|prefeito|" +
            "coordenadas|situa-se no distrito|é composto por)",
        RegexOption.IGNORE_CASE,
    )
    override val contextDependent = Regex("^(Ele|Ela|Eles|Elas|Este|Esta|Estes|Estas|Seu|Sua|Seus|Suas|No entanto|Mas|Também|Ambos|Lá|Além disso)(?!\\p{L})")

    private val definitionVerbs = listOf(" é um", " é uma", " é o", " é a", " era um", " era uma", " são ")

    override fun isDefinition(sentence: String, title: String): Boolean {
        val head = sentence.take(title.length + 40).lowercase()
        val name = title.lowercase()
        return sentence.length < 220 &&
            (head.startsWith(name) || listOf("o ", "a ", "os ", "as ").any { head.startsWith(it + name) }) &&
            definitionVerbs.any { it in head }
    }

    /** Portuguese descriptions ("município de Portugal") don't say their gender; the article's first sentence does. */
    override fun identityLine(description: String): String? = null

    override fun kindLine(kind: PlaceKind) = "É ${placeNoun(kind)}."

    override fun leads(kind: SectionKind): List<String> = when (kind) {
        SectionKind.NAME -> listOf("Já se perguntou de onde vem o nome?", "O nome tem uma história própria.")
        SectionKind.LEGEND -> listOf("Há também uma lenda.", "E depois há a história que se conta.")
        SectionKind.HISTORY -> listOf("Um pouco de história.", "Vamos recuar no tempo.", "A história deixou aqui a sua marca.")
        SectionKind.CULTURE -> listOf("A cultura tem aqui raízes profundas.", "Algo sobre a vida local.")
        SectionKind.LANDMARKS -> listOf("Olhe com atenção e vai reparar nisto.", "Um detalhe que vale a pena conhecer.")
        SectionKind.NATURE -> listOf("Agora, a paisagem.", "A natureza tem aqui a sua própria história.")
        SectionKind.CLIMATE -> listOf("Quanto ao clima.")
        else -> emptyList()
    }

    override val interestKeywords = mapOf(
        Interest.HISTORY to listOf(
            "batalha", "castelo", "forte", "guerra", "antig", "medieval", "roman", "século", "históric", "ruína",
            "monumento", "memorial", "arqueológ", "abadia", "palácio", "fundad", "cerco", "mosteiro", "convento", "solar",
            "dinastia", "viking", "pré-histór", "sepultura", "túmulo", "fortaleza", "muralha", "cidadela", "patrimóni",
            "património", "demolid", "reconstruíd",
        ),
        Interest.NATURE to listOf(
            "rio", "lago", "montanha", "serra", "floresta", "reserva natural", "parque nacional", "vale", "colina", "ilha",
            "glaciar", "duna", "charneca", "pântano", "espécie", "clima", "geológ",
        ),
        Interest.CULTURE to listOf(
            "museu", "festival", "teatro", "arte", "música", "pintor", "escritor", "culinária", "tradição", "religi",
            "igreja", "catedral", "mesquita", "templo", "universidade",
        ),
        Interest.ARCHITECTURE to listOf(
            "edifício", "torre", "ponte", "arquitet", "moinho", "casa", "castelo", "igreja", "catedral", "estação",
            "canal", "eclusa", "barragem", "arranha-céu",
        ),
    )
    override val placeNouns = setOf(
        "cidade", "vila", "aldeia", "município", "freguesia", "concelho", "localidade", "capital", "província", "região",
        "distrito", "condado", "bairro", "lugar", "antiga", "povoação",
    )
    override val notPlaceNouns = setOf("igreja", "castelo", "forte", "moinho")

    override fun hereOpeners(title: String, area: Boolean, flying: Boolean): List<String> = when {
        area && flying -> listOf("Lá em baixo fica agora $title.", "Sobrevoamos agora $title.")
        area -> listOf(
            "Bem-vindo a $title.",
            "Já estamos em $title.",
            "Agora atravessa $title.",
            "E aqui estamos, em $title.",
        )
        else -> listOf(
            "Olhe à sua volta: está mesmo ao lado de $title.",
            "Mesmo aqui fica $title.",
            "Pare um momento. Isto é $title.",
        )
    }

    // A distance comes with its own "cerca de", so "a $distance" reads "a cerca de 800 metros" or "a um quilómetro".
    override fun aheadOpeners(title: String, distance: String) = listOf(
        "Mais à frente, a $distance, fica $title.",
        "Fique atento à estrada: a $distance daqui está $title.",
        "Logo adiante, a $distance daqui, está $title.",
    )

    override fun sideOpeners(title: String, left: Boolean, distance: String, window: Boolean): List<String> {
        val side = if (left) "esquerda" else "direita"
        val sideAdjective = if (left) "esquerdo" else "direito"
        return if (window) {
            listOf(
                "Olhe pela janela da $side. A $distance fica $title.",
                "Se está sentado do lado $sideAdjective, olhe agora: a $distance está $title.",
            )
        } else {
            listOf(
                "Olhe para a $side. A $distance fica $title.",
                "À sua $side, a $distance, está $title.",
                "Para a $side, a $distance daqui, vai encontrar $title.",
            )
        }
    }

    override fun behindOpeners(title: String, distance: String) =
        listOf("Mesmo atrás de nós, a $distance, fica $title.", "Acabamos de passar por $title, a $distance atrás de nós.")

    override fun compassOpeners(title: String, direction: String, distance: String) = listOf(
        "A $distance, em direção ao $direction, fica $title.",
        "Não muito longe daqui, a $distance em direção ao $direction, está $title.",
    )

    override fun afieldOpeners(title: String, relative: RelativePosition): List<String> = when (relative) {
        RelativePosition.Here -> listOf("Tudo à nossa volta é $title.", "A região onde estamos é $title.")
        is RelativePosition.Ahead -> {
            val d = distance(relative.distanceM)
            listOf("Mais adiante no caminho, a $d, fica $title.", "Ainda fora de vista, a $d à frente, está $title.")
        }
        is RelativePosition.Side -> {
            val d = distance(relative.distanceM)
            val side = if (relative.left) "esquerda" else "direita"
            listOf("Um pouco mais longe, a $d à sua $side, fica $title.", "Fora de vista, a $d para a $side, está $title.")
        }
        is RelativePosition.Behind -> {
            val d = distance(relative.distanceM)
            listOf("A $d atrás de nós fica $title.", "Voltando pelo caminho que fizemos, a $d, está $title.")
        }
        is RelativePosition.Compass -> {
            val d = distance(relative.distanceM)
            val direction = compassName(relative.bearingDeg)
            listOf("Um pouco mais longe, a $d em direção ao $direction, fica $title.", "Fora de vista, a $d em direção ao $direction, está $title.")
        }
    }

    override fun transitions(title: String, where: String, mentioned: Boolean): List<String> = if (mentioned) {
        listOf("O que nos leva a $title, $where.", "E a história continua em $title, $where.")
    } else {
        listOf(
            "Um pouco mais adiante, $where, vai encontrar $title.",
            "Não muito longe dali, $where, fica $title.",
            "E já que estamos aqui: $where está $title.",
        )
    }

    override fun closings(relative: RelativePosition, mode: TravelMode): List<String> = when {
        relative is RelativePosition.Behind -> emptyList()
        mode == TravelMode.WALKING || mode == TravelMode.STATIONARY ->
            listOf("Tire um momento para olhar à sua volta.", "Merece um olhar mais atento, se tiver tempo.")
        mode == TravelMode.CYCLING -> listOf("Merece uma pausa, se as pernas deixarem.", "Algo a observar enquanto pedala.")
        relative is RelativePosition.Here -> listOf("Mantenha os olhos abertos enquanto atravessamos.")
        else -> listOf("Fique atento quando passarmos.", "Algo a lembrar para uma próxima visita.")
    }

    override fun where(relative: RelativePosition): String = when (relative) {
        RelativePosition.Here -> "mesmo aqui"
        is RelativePosition.Ahead -> "logo em frente, a ${distance(relative.distanceM)}"
        is RelativePosition.Side -> "à sua ${if (relative.left) "esquerda" else "direita"}, a ${distance(relative.distanceM)}"
        is RelativePosition.Behind -> "mesmo atrás de nós"
        is RelativePosition.Compass -> "a ${distance(relative.distanceM)} em direção ao ${compassName(relative.bearingDeg)}"
    }

    override fun distance(meters: Double): String = when {
        meters < 950 -> "cerca de ${((meters / 100).roundToInt() * 100).coerceAtLeast(100)} metros"
        meters < 1_050 -> "um quilómetro"
        meters < 9_950 -> {
            val km = (meters / 100).roundToInt() / 10.0
            val text = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString().replace('.', ',')
            "cerca de $text quilómetros"
        }
        else -> "cerca de ${(meters / 1000).roundToInt()} quilómetros"
    }

    override val compassNames = listOf("norte", "nordeste", "leste", "sudeste", "sul", "sudoeste", "oeste", "noroeste")

    /** With its indefinite article, which depends on the noun's gender. */
    override fun placeNoun(kind: PlaceKind): String = when (kind) {
        PlaceKind.TOWN_HALL -> "uma câmara municipal"
        PlaceKind.CATHEDRAL -> "uma catedral"
        PlaceKind.CHURCH -> "uma igreja"
        PlaceKind.CHAPEL -> "uma capela"
        PlaceKind.MOSQUE -> "uma mesquita"
        PlaceKind.SYNAGOGUE -> "uma sinagoga"
        PlaceKind.TEMPLE -> "um templo"
        PlaceKind.MONASTERY -> "um mosteiro"
        PlaceKind.MUSEUM -> "um museu"
        PlaceKind.GALLERY -> "uma galeria de arte"
        PlaceKind.LIBRARY -> "uma biblioteca"
        PlaceKind.UNIVERSITY -> "um edifício universitário"
        PlaceKind.COLLEGE -> "uma escola superior"
        PlaceKind.COURTHOUSE -> "um tribunal"
        PlaceKind.THEATRE -> "um teatro"
        PlaceKind.CONCERT_HALL -> "uma sala de concertos"
        PlaceKind.ARTS_CENTRE -> "um centro cultural"
        PlaceKind.STATION -> "uma estação ferroviária"
        PlaceKind.CASTLE -> "um castelo"
        PlaceKind.PALACE -> "um palácio"
        PlaceKind.FORT -> "uma fortaleza"
        PlaceKind.CITY_GATE -> "uma porta da cidade"
        PlaceKind.CITY_WALL -> "uma muralha"
        PlaceKind.TOWER -> "uma torre"
        PlaceKind.WINDMILL -> "um moinho de vento"
        PlaceKind.WATERMILL -> "um moinho de água"
        PlaceKind.LIGHTHOUSE -> "um farol"
        PlaceKind.MANOR -> "um solar"
        PlaceKind.RUINS -> "uma ruína"
        PlaceKind.ARCHAEOLOGICAL_SITE -> "um sítio arqueológico"
        PlaceKind.MONUMENT -> "um monumento"
        PlaceKind.MEMORIAL -> "um memorial"
        PlaceKind.STATUE -> "uma estátua"
        PlaceKind.ARTWORK -> "uma obra de arte"
        PlaceKind.FOUNTAIN -> "uma fonte"
        PlaceKind.SHIP -> "um navio histórico"
        PlaceKind.HISTORIC_BUILDING -> "um edifício histórico"
        PlaceKind.BUILDING -> "um edifício"
        PlaceKind.ATTRACTION -> "um ponto de interesse"
        PlaceKind.VIEWPOINT -> "um miradouro"
    }

    override fun withDenomination(denomination: String?, noun: String): String = noun

    override fun heritage(heritage: Heritage): String = when (heritage) {
        Heritage.WorldHeritage -> "parte de um sítio Património Mundial da UNESCO"
        Heritage.Rijksmonument -> "um rijksmonument, um monumento nacional neerlandês"
        is Heritage.ListedStatus, Heritage.Listed -> "um monumento protegido"
        Heritage.National -> "um monumento nacional"
    }

    override fun theOne(kind: PlaceKind, description: String): String? = null

    override fun formerName(name: String) = listOf("O seu nome antigo era $name.", "No passado, o nome era $name.")

    override fun dateLines(kind: PlaceKind, date: HistoricDate, ago: String): List<String> {
        val decade = date.year / 10 * 10
        val from = when (date.precision) {
            HistoricDate.Precision.YEAR -> "de ${date.year}"
            HistoricDate.Precision.DECADE -> "da década de $decade"
            HistoricDate.Precision.CENTURY -> "do século ${century(date)}"
        }
        val to = when (date.precision) {
            HistoricDate.Precision.YEAR -> "a ${date.year}"
            HistoricDate.Precision.DECADE -> "à década de $decade"
            HistoricDate.Precision.CENTURY -> "ao século ${century(date)}"
        }
        val since = when (date.precision) {
            HistoricDate.Precision.YEAR -> "desde ${date.year}"
            HistoricDate.Precision.DECADE -> "desde a década de $decade"
            HistoricDate.Precision.CENTURY -> "desde o século ${century(date)}"
        }
        return when {
            kind.institution -> listOf("A sua fundação data $from$ago.", "As suas origens remontam $to.")
            kind.isObject -> listOf("A sua colocação aqui data $from$ago.", "Está aqui $since.")
            else -> listOf("A sua construção data $from$ago.", "Data $from.", "Está aqui $since.")
        }
    }

    /** "quinze": Portuguese reads centuries as cardinals, spelled out so every voice reads them right. */
    private fun century(date: HistoricDate): String = listOf(
        "primeiro", "segundo", "terceiro", "quarto", "quinto", "sexto", "sétimo", "oitavo", "nono", "décimo", "onze",
        "doze", "treze", "catorze", "quinze", "dezasseis", "dezassete", "dezoito", "dezanove", "vinte", "vinte e um",
    ).getOrNull(date.century - 1) ?: "${date.century}"

    override fun ago(years: Int) = ", há mais de $years anos"

    override fun architectLines(kind: PlaceKind, names: String): List<String> = when {
        kind.isObject -> listOf("É obra de $names.", "O seu autor é $names.")
        kind.institution -> listOf("O edifício foi projetado por $names.", "O arquiteto do edifício foi $names.")
        else -> listOf("O projeto é de $names.", "O arquiteto foi $names.")
    }

    override fun styleLines(style: String) = listOf("Repare no estilo: $style.", "O seu estilo é conhecido como $style.")

    override fun heritageLines(heritage: String) = listOf("É $heritage.", "Hoje é $heritage.")

    override fun namedAfterLines(name: String) = listOf("O nome homenageia $name.", "Deve o nome a $name.")

    override fun occupantLines(name: String) = listOf("Hoje acolhe $name.", "Atualmente funciona aqui $name.")

    override fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " e " + names.last()
    }
}
