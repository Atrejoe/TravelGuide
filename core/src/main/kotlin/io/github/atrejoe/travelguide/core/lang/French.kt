package io.github.atrejoe.travelguide.core.lang

import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.RelativePosition
import io.github.atrejoe.travelguide.core.story.SectionKind
import kotlin.math.roundToInt

/**
 * French stories from the French Wikipedia (issue #68), addressing the listener as "vous".
 * Phrases avoid agreeing with a place's gender: "Sa construction remonte à …" rather than
 * "Il a été construit".
 */
object French : StoryLanguage("fr", "French", "Français") {
    override val sectionRules = listOf(
        SectionKind.SKIP to listOf(
            "démographie", "population", "administration", "politique", "économie", "transport", "enseignement",
            "éducation", "sport", "jumelage", "références", "notes", "bibliographie", "liens externes", "voir aussi",
            "galerie", "personnalités", "infrastructure", "médias", "santé", "statistique", "quartiers", "liste",
            "sources", "annexes",
        ),
        SectionKind.NAME to listOf("toponymie", "étymologie", "nom"),
        SectionKind.LEGEND to listOf("légende", "folklore", "mythe", "fantôme", "mystère"),
        SectionKind.HISTORY to listOf("histoire", "historique", "origine", "fondation", "moyen âge", "moyen-âge", "guerre", "antiquité", "préhistoire", "siècle"),
        SectionKind.CULTURE to listOf("culture", "art", "festival", "cuisine", "gastronomie", "musique", "religion", "tradition", "événement"),
        SectionKind.LANDMARKS to listOf("monument", "architecture", "lieux", "patrimoine", "bâtiment", "édifice", "curiosité"),
        SectionKind.NATURE to listOf("géographie", "géologie", "nature", "paysage", "flore", "faune", "environnement", "cours", "hydrographie", "situation"),
        SectionKind.CLIMATE to listOf("climat", "météo"),
    )

    override val abbreviations = setOf(
        "st", "ste", "dr", "pr", "m", "mme", "mlle", "mgr", "av", "apr", "j.-c", "env", "etc", "no", "n°", "km", "p",
        "cf", "ex", "vol", "éd", "s",
    )
    override val sentenceStarters = setOf(
        "Le", "La", "Les", "Un", "Une", "Il", "Elle", "Ils", "Elles", "Ce", "Cette", "Ces", "Son", "Sa", "Ses", "En",
        "Au", "Aux", "Après", "Avant", "Pendant", "Depuis", "Dans", "De", "Du", "Des", "On",
    )

    override val remarkable = Regex(
        "(?<!\\p{L})(plus ancien(ne)?s?|plus grand(e)?s?|plus haut(e)?s?|plus long(ue)?s?|plus petit(e)?s?|premier|première|" +
            "seul(e)?|dernier|dernière|célèbres?|fameux|fameuse|réputé(e)?|remarquables?|uniques?|légendes?|légendaires?|" +
            "mythes?|selon|surnommé(e)?|connu(e)? sous le nom|détruit(e)?s?|incendi(e|é|ée)|inondations?|peste|siège|" +
            "assiégé(e)?|batailles?|traité|découvert(e)?|inventé(e)?|survécu|miracles?|secrets?|mystères?|roi|reine|" +
            "empereur|romain(e)?s?|vikings?|médiéva(l|le|ux|les)|moines?|chevaliers?|saint(e)?s?|pèlerin(s|e|es)?|trésors?|ruines?)(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )
    override val dramatic = Regex(
        "(?<!\\p{L})(empoisonn|intoxication|massacre|meurtre|assassin|exécut|décapit|sorcière|catastrophe|désastre|explosion|naufrage|séisme|tremblement de terre|avalanche|épidémie|peste|famine|émeute|révolte|insurrection|mutinerie|siège|bataille|braquage|enlèvement|contrebande|évasion|scandale|mystère|mystérieu|hanté|fantôme|malédiction|miracle|apparition|tragédie|hystérie)",
        RegexOption.IGNORE_CASE,
    )
    override val nameOrigin = Regex(
        "(?<!\\p{L})(doit son nom|tire son nom|nommé(e)? d'après|dérivé|vient du|vient de|le nom|signifie|signification)(?!\\p{L})",
        RegexOption.IGNORE_CASE,
    )
    override val dated = Regex("(?<!\\p{L})(1[0-9]{3}|20[0-2][0-9]|[0-9]{1,2}e siècle|[XVI]+e siècle|av\\. J\\.-C|apr\\. J\\.-C)")
    override val administrative = Regex(
        "(?<!\\p{L})(habitants|population|recensement|commune de|km2|km²|kilomètres carrés|code postal|densité|pour cent|" +
            "ménages|médian|administrati|chef-lieu|statistiques?|élus?|conseil municipal|maire|coordonnées|" +
            "située? dans le département|se compose de)",
        RegexOption.IGNORE_CASE,
    )
    override val contextDependent = Regex("^(Il|Elle|Ils|Elles|Ce|Cette|Ces|Celui|Celle|Son|Sa|Ses|Leur|Leurs|Cependant|Mais|Aussi|Toutefois|Là)(?!\\p{L})")

    private val definitionVerbs = listOf(" est un", " est une", " est le", " est la", " est l'", " était un", " était une", " sont ")

    override fun isDefinition(sentence: String, title: String): Boolean {
        val head = sentence.take(title.length + 40).lowercase()
        val name = title.lowercase()
        return sentence.length < 220 &&
            (head.startsWith(name) || listOf("le ", "la ", "les ", "l'", "l’").any { head.startsWith(it + name) }) &&
            definitionVerbs.any { it in head }
    }

    /** French descriptions ("commune française") don't say their gender; the article's first sentence does. */
    override fun identityLine(description: String): String? = null

    override fun kindLine(kind: PlaceKind) = "C'est ${placeNoun(kind)}."

    override fun leads(kind: SectionKind): List<String> = when (kind) {
        SectionKind.NAME -> listOf("D'où vient ce nom ?", "Le nom a sa propre histoire.")
        SectionKind.LEGEND -> listOf("Il existe aussi une légende.", "Et puis il y a l'histoire que l'on raconte.")
        SectionKind.HISTORY -> listOf("Un peu d'histoire.", "Remontons le temps.", "L'histoire a laissé ici sa marque.")
        SectionKind.CULTURE -> listOf("La culture est ici profondément ancrée.", "Un mot sur la vie locale.")
        SectionKind.LANDMARKS -> listOf("Regardez bien, et vous remarquerez ceci.", "Un détail qui vaut le coup.")
        SectionKind.NATURE -> listOf("Parlons maintenant du paysage.", "La nature a ici sa propre histoire.")
        SectionKind.CLIMATE -> listOf("Quant au climat.")
        else -> emptyList()
    }

    override val interestKeywords = mapOf(
        Interest.HISTORY to listOf(
            "bataille", "château", "fort", "guerre", "antique", "médiév", "romain", "siècle", "histori", "ruine",
            "monument", "mémorial", "archéolog", "abbaye", "palais", "fondé", "siège", "monastère", "manoir", "domaine",
            "dynastie", "viking", "préhistor", "sépulture", "tumulus", "forteresse", "rempart", "citadelle", "patrimoine",
            "ancien", "démoli", "reconstruit",
        ),
        Interest.NATURE to listOf(
            "rivière", "fleuve", "lac", "montagne", "forêt", "réserve naturelle", "parc national", "vallée", "colline",
            "île", "glacier", "dune", "lande", "marais", "oiseau", "espèce", "climat", "géolog",
        ),
        Interest.CULTURE to listOf(
            "musée", "festival", "théâtre", "art", "musique", "peintre", "écrivain", "cuisine", "tradition", "religi",
            "église", "cathédrale", "mosquée", "temple", "université",
        ),
        Interest.ARCHITECTURE to listOf(
            "bâtiment", "édifice", "tour", "pont", "architecte", "moulin", "maison", "château", "église", "cathédrale",
            "gare", "canal", "écluse", "barrage", "gratte-ciel",
        ),
    )
    override val placeNouns = setOf(
        "ville", "village", "commune", "capitale", "province", "région", "département", "comté", "district", "quartier",
        "hameau", "arrondissement", "canton", "ancienne", "localité", "lieu",
    )
    override val notPlaceNouns = setOf("église", "château", "fort", "moulin")

    /** "au nord", "à l'est". */
    private fun toward(direction: String) = if (direction.first() in "aeiouéè") "à l'$direction" else "au $direction"

    override fun hereOpeners(title: String, area: Boolean, flying: Boolean): List<String> = when {
        area && flying -> listOf("Sous nous s'étend maintenant $title.", "Nous survolons $title.")
        area -> listOf(
            "Bienvenue à $title.",
            "Nous venons d'arriver à $title.",
            "Vous traversez maintenant $title.",
            "Et nous voici à $title.",
        )
        else -> listOf(
            "Regardez autour de vous : vous êtes tout près de $title.",
            "Juste ici se trouve $title.",
            "Arrêtez-vous un instant. Voici $title.",
        )
    }

    override fun aheadOpeners(title: String, distance: String) = listOf(
        "Droit devant, à environ $distance, se trouve $title.",
        "Gardez les yeux sur la route : à environ $distance devant nous se trouve $title.",
        "Un peu plus loin, à quelque $distance d'ici, se trouve $title.",
    )

    override fun sideOpeners(title: String, left: Boolean, distance: String, window: Boolean): List<String> {
        val side = if (left) "gauche" else "droite"
        // "le côté droit", but "votre droite".
        val sideAdjective = if (left) "gauche" else "droit"
        return if (window) {
            listOf(
                "Regardez par la fenêtre de $side. À environ $distance se trouve $title.",
                "Si vous êtes assis côté $sideAdjective, regardez maintenant : à environ $distance se trouve $title.",
            )
        } else {
            listOf(
                "Regardez sur votre $side. À environ $distance se trouve $title.",
                "Sur votre $side, à quelque $distance, se trouve $title.",
                "Du côté $sideAdjective, à environ $distance d'ici, vous trouverez $title.",
            )
        }
    }

    override fun behindOpeners(title: String, distance: String) =
        listOf("Juste derrière nous, à environ $distance, se trouve $title.", "Nous venons de passer $title, à environ $distance derrière nous.")

    override fun compassOpeners(title: String, direction: String, distance: String) = listOf(
        "À environ $distance ${toward(direction)} se trouve $title.",
        "Non loin d'ici, à $distance ${toward(direction)}, se trouve $title.",
    )

    override fun afieldOpeners(title: String, relative: RelativePosition): List<String> = when (relative) {
        RelativePosition.Here -> listOf("Tout autour de nous s'étend $title.", "La région où nous sommes, c'est $title.")
        is RelativePosition.Ahead -> {
            val d = distance(relative.distanceM)
            listOf("Plus loin sur la route, à quelque $d devant nous, se trouve $title.", "Encore hors de vue, à environ $d devant nous, se trouve $title.")
        }
        is RelativePosition.Side -> {
            val d = distance(relative.distanceM)
            val side = if (relative.left) "gauche" else "droite"
            listOf("Un peu plus loin, à environ $d sur votre $side, se trouve $title.", "Hors de vue, à quelque $d sur la $side, se trouve $title.")
        }
        is RelativePosition.Behind -> {
            val d = distance(relative.distanceM)
            listOf("À quelque $d derrière nous se trouve $title.", "En revenant sur nos pas, à environ $d, se trouve $title.")
        }
        is RelativePosition.Compass -> {
            val d = distance(relative.distanceM)
            val toward = toward(compassName(relative.bearingDeg))
            listOf("Un peu plus loin, à environ $d $toward, se trouve $title.", "Hors de vue, à quelque $d $toward, se trouve $title.")
        }
    }

    override fun transitions(title: String, where: String, mentioned: Boolean): List<String> = if (mentioned) {
        listOf("Ce qui nous amène à $title, $where.", "Et l'histoire continue à $title, $where.")
    } else {
        listOf(
            "Un peu plus loin, $where, vous trouverez $title.",
            "Non loin de là, $where, se trouve $title.",
            "Et tant que nous y sommes : $where se trouve $title.",
        )
    }

    override fun closings(relative: RelativePosition, mode: TravelMode): List<String> = when {
        relative is RelativePosition.Behind -> emptyList()
        mode == TravelMode.WALKING || mode == TravelMode.STATIONARY ->
            listOf("Prenez un moment pour regarder autour de vous.", "Cela vaut le coup d'œil, si vous avez le temps.")
        mode == TravelMode.CYCLING -> listOf("Cela vaut un arrêt, si vos jambes sont d'accord.", "Quelque chose à repérer en pédalant.")
        relative is RelativePosition.Here -> listOf("Ouvrez l'œil pendant que nous traversons.")
        else -> listOf("Gardez l'œil ouvert en passant.", "Une idée pour une prochaine visite.")
    }

    override fun where(relative: RelativePosition): String = when (relative) {
        RelativePosition.Here -> "juste ici"
        is RelativePosition.Ahead -> "droit devant, à environ ${distance(relative.distanceM)}"
        is RelativePosition.Side -> "sur votre ${if (relative.left) "gauche" else "droite"}, à environ ${distance(relative.distanceM)}"
        is RelativePosition.Behind -> "juste derrière nous"
        is RelativePosition.Compass -> "à ${distance(relative.distanceM)} ${toward(compassName(relative.bearingDeg))}"
    }

    override fun distance(meters: Double): String = when {
        meters < 950 -> "${((meters / 100).roundToInt() * 100).coerceAtLeast(100)} mètres"
        meters < 1_050 -> "un kilomètre"
        meters < 9_950 -> {
            val km = (meters / 100).roundToInt() / 10.0
            val text = if (km % 1.0 == 0.0) km.toInt().toString() else km.toString().replace('.', ',')
            "$text kilomètres"
        }
        else -> "${(meters / 1000).roundToInt()} kilomètres"
    }

    override val compassNames = listOf("nord", "nord-est", "est", "sud-est", "sud", "sud-ouest", "ouest", "nord-ouest")

    /** With its indefinite article, which depends on the noun's gender. */
    override fun placeNoun(kind: PlaceKind): String = when (kind) {
        PlaceKind.TOWN_HALL -> "un hôtel de ville"
        PlaceKind.CATHEDRAL -> "une cathédrale"
        PlaceKind.CHURCH -> "une église"
        PlaceKind.CHAPEL -> "une chapelle"
        PlaceKind.MOSQUE -> "une mosquée"
        PlaceKind.SYNAGOGUE -> "une synagogue"
        PlaceKind.TEMPLE -> "un temple"
        PlaceKind.MONASTERY -> "un monastère"
        PlaceKind.MUSEUM -> "un musée"
        PlaceKind.GALLERY -> "une galerie d'art"
        PlaceKind.LIBRARY -> "une bibliothèque"
        PlaceKind.UNIVERSITY -> "un bâtiment universitaire"
        PlaceKind.COLLEGE -> "une école supérieure"
        PlaceKind.COURTHOUSE -> "un palais de justice"
        PlaceKind.THEATRE -> "un théâtre"
        PlaceKind.CONCERT_HALL -> "une salle de concert"
        PlaceKind.ARTS_CENTRE -> "un centre culturel"
        PlaceKind.STATION -> "une gare"
        PlaceKind.CASTLE -> "un château"
        PlaceKind.PALACE -> "un palais"
        PlaceKind.FORT -> "une forteresse"
        PlaceKind.CITY_GATE -> "une porte de ville"
        PlaceKind.CITY_WALL -> "un rempart"
        PlaceKind.TOWER -> "une tour"
        PlaceKind.WINDMILL -> "un moulin à vent"
        PlaceKind.WATERMILL -> "un moulin à eau"
        PlaceKind.LIGHTHOUSE -> "un phare"
        PlaceKind.MANOR -> "un manoir"
        PlaceKind.RUINS -> "une ruine"
        PlaceKind.ARCHAEOLOGICAL_SITE -> "un site archéologique"
        PlaceKind.MONUMENT -> "un monument"
        PlaceKind.MEMORIAL -> "un mémorial"
        PlaceKind.STATUE -> "une statue"
        PlaceKind.ARTWORK -> "une œuvre d'art"
        PlaceKind.FOUNTAIN -> "une fontaine"
        PlaceKind.SHIP -> "un navire historique"
        PlaceKind.HISTORIC_BUILDING -> "un bâtiment historique"
        PlaceKind.BUILDING -> "un bâtiment"
        PlaceKind.ATTRACTION -> "un lieu à voir"
        PlaceKind.VIEWPOINT -> "un point de vue"
    }

    override fun withDenomination(denomination: String?, noun: String): String = noun

    override fun heritage(heritage: Heritage): String = when (heritage) {
        Heritage.WorldHeritage -> "un site du patrimoine mondial de l'UNESCO"
        Heritage.Rijksmonument -> "un rijksmonument, un monument national néerlandais"
        is Heritage.ListedStatus, Heritage.Listed -> "un monument protégé"
        Heritage.National -> "un monument national"
    }

    override fun theOne(kind: PlaceKind, description: String): String? = null

    override fun formerName(name: String) = listOf("Autrefois, on l'appelait $name.", "Son ancien nom est $name.")

    override fun dateLines(kind: PlaceKind, date: HistoricDate, ago: String): List<String> {
        val decade = date.year / 10 * 10
        val to = when (date.precision) {
            HistoricDate.Precision.YEAR -> "à ${date.year}"
            HistoricDate.Precision.DECADE -> "aux années $decade"
            HistoricDate.Precision.CENTURY -> "au ${century(date)} siècle"
        }
        val from = when (date.precision) {
            HistoricDate.Precision.YEAR -> "de ${date.year}"
            HistoricDate.Precision.DECADE -> "des années $decade"
            HistoricDate.Precision.CENTURY -> "du ${century(date)} siècle"
        }
        val since = when (date.precision) {
            HistoricDate.Precision.YEAR -> "depuis ${date.year}"
            HistoricDate.Precision.DECADE -> "depuis les années $decade"
            HistoricDate.Precision.CENTURY -> "depuis le ${century(date)} siècle"
        }
        return when {
            kind.institution -> listOf("Sa fondation remonte $to$ago.", "Ses origines remontent $to.")
            kind.isObject -> listOf("Sa mise en place remonte $to$ago.", "Sa présence ici remonte $to.")
            else -> listOf("Sa construction remonte $to$ago.", "Sa construction date $from.", "L'édifice se dresse ici $since.")
        }
    }

    /** "quinzième", spelled out so every voice reads it right. */
    private fun century(date: HistoricDate): String = listOf(
        "premier", "deuxième", "troisième", "quatrième", "cinquième", "sixième", "septième", "huitième", "neuvième",
        "dixième", "onzième", "douzième", "treizième", "quatorzième", "quinzième", "seizième", "dix-septième",
        "dix-huitième", "dix-neuvième", "vingtième", "vingt-et-unième",
    ).getOrNull(date.century - 1) ?: "${date.century}e"

    override fun ago(years: Int) = ", il y a plus de $years ans"

    override fun architectLines(kind: PlaceKind, names: String): List<String> = when {
        kind.isObject -> listOf("C'est l'œuvre de $names.", "Son auteur est $names.")
        kind.institution -> listOf("Le bâtiment a été conçu par $names.", "L'architecte du bâtiment était $names.")
        else -> listOf("On doit ses plans à $names.", "L'architecte était $names.")
    }

    override fun styleLines(style: String) = listOf("Remarquez son style : $style.", "Son style est connu sous le nom de $style.")

    override fun heritageLines(heritage: String) = listOf("C'est $heritage.", "Aujourd'hui, c'est $heritage.")

    override fun namedAfterLines(name: String) = listOf("Son nom rend hommage à $name.", "On lui a donné le nom de $name.")

    override fun occupantLines(name: String) = listOf("Aujourd'hui, on y trouve $name.", "C'est aujourd'hui le siège de $name.")

    override fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " et " + names.last()
    }
}
