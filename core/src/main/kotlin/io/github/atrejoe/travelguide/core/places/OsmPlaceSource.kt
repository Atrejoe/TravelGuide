package io.github.atrejoe.travelguide.core.places

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.rail.OsmFeature
import io.github.atrejoe.travelguide.core.rail.OverpassClient
import io.github.atrejoe.travelguide.core.story.HistoricDate
import io.github.atrejoe.travelguide.core.story.PlaceFacts
import io.github.atrejoe.travelguide.core.story.PlaceKind
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.wiki.WikidataClient
import io.github.atrejoe.travelguide.core.wiki.WikidataEntity
import io.github.atrejoe.travelguide.core.wiki.WikidataLabel
import kotlinx.coroutines.CancellationException
import java.time.Year

/** Something that can find buildings and institutions near a point, for walking tours. */
fun interface PlaceSource {
    suspend fun places(center: GeoPoint, radiusM: Int): List<Poi>
}

/** Something that knows which town a point lies in, as a place with its Wikipedia article. */
fun interface TownSource {
    /** The town at [point], or null when unknown or it has no article. */
    suspend fun town(point: GeoPoint): Poi?
}

/**
 * Buildings and institutions from OpenStreetMap (Overpass), enriched with Wikidata where the
 * object links to an item: its description, when it was built, its architect and style, whether
 * it is a listed monument, whom it is named after and who uses it. Places with nothing to tell
 * beyond their name are left out.
 *
 * Wikidata is optional: when it can't be reached the OpenStreetMap facts are used alone.
 */
class OsmPlaceSource(
    private val overpass: OverpassClient,
    private val wikidata: WikidataClient? = null,
    private val lang: String = "en",
    private val currentYear: Int = Year.now().value,
) : PlaceSource {

    override suspend fun places(center: GeoPoint, radiusM: Int): List<Poi> {
        val features = overpass.places(center, radiusM)
            .mapNotNull { f -> PlaceClassifier.kind(f.tags)?.let { f to it } }
        val ids = features.mapNotNull { (f, _) -> itemId(f.tags) }
        val entities = optional { wikidata?.entities(ids) }.orEmpty()
        val references = entities.values.flatMap { e -> REFERENCES.flatMap { e.items[it].orEmpty().take(2) } }
        val labels = optional { wikidata?.labels(references) }.orEmpty()
        val pois = features.mapNotNull { (f, kind) -> toPoi(f, kind, itemId(f.tags)?.let(entities::get), labels) }
        return dedupe(pois)
    }

    /** Wikidata is a nice-to-have: failures leave the OpenStreetMap facts. */
    private suspend fun <T> optional(block: suspend () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    internal fun toPoi(f: OsmFeature, osmKind: PlaceKind, entity: WikidataEntity?, labels: Map<String, WikidataLabel>): Poi? {
        val tags = f.tags
        val name = tags["name"]?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        fun label(id: String): String? = labels[id]?.text
        // Class labels ("Gothic architecture") read badly in another language; names are fine.
        fun ownLanguage(id: String): String? = labels[id]?.takeIf { it.lang == lang || it.lang == "mul" }?.text

        val claims = entity?.items.orEmpty()
        val kindLabel = claims["P31"].orEmpty().firstNotNullOfOrNull { ownLanguage(it) }
            ?.takeIf { osmKind in GENERIC && !it.contains("monument", ignoreCase = true) && !it.contains("listed", ignoreCase = true) }
        val since = entity?.times?.get("P571")?.firstNotNullOfOrNull { HistoricDate.parseWikidata(it.time, it.precision, currentYear) }
            ?: HistoricDate.parseOsm(tags["start_date"], currentYear)
        val architects = claims["P84"].orEmpty().mapNotNull(::label).ifEmpty {
            tags["architect"]?.split(';')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
        }
        val facts = PlaceFacts(
            kind = osmKind,
            description = entity?.description?.takeIf { usableDescription(it) },
            since = since,
            architects = architects.take(2),
            styles = claims["P149"].orEmpty().mapNotNull(::ownLanguage),
            heritage = heritage(entity, tags),
            namedAfter = claims["P138"].orEmpty().firstNotNullOfOrNull(::label),
            occupant = claims["P466"].orEmpty().firstNotNullOfOrNull(::label),
            formerName = tags["old_name"]?.substringBefore(';')?.trim()?.takeIf { it.isNotEmpty() && !it.equals(name, ignoreCase = true) },
        )
        val wikiTitle = entity?.sitelink
            ?: tags["wikipedia"]?.takeIf { it.startsWith("$lang:") }?.substringAfter(':')?.takeIf { it.isNotBlank() }
        // A name and a kind alone make a dull story; an article makes up for missing facts.
        if (facts.substance == 0 && wikiTitle == null) return null

        val qid = entity?.id ?: itemId(tags)
        return Poi(
            pageId = syntheticId(f),
            title = tags["name:$lang"]?.takeIf { it.isNotBlank() } ?: englishLabel(entity) ?: name,
            point = f.point,
            type = osmKind.poiType,
            description = facts.description ?: kindLabel ?: PlaceClassifier.noun(osmKind, tags),
            url = when {
                wikiTitle != null -> "https://$lang.wikipedia.org/wiki/" + wikiTitle.replace(' ', '_')
                qid != null -> "https://www.wikidata.org/wiki/$qid"
                else -> f.url
            },
            lang = lang,
            wikidataId = qid,
            wikiTitle = wikiTitle,
            facts = facts,
        )
    }

    /** The Wikidata label in our language, without a disambiguating ", Utrecht" or " (church)". */
    private fun englishLabel(entity: WikidataEntity?): String? {
        val label = entity?.label?.takeIf { it.lang == lang } ?: return null
        return label.text.substringBefore(" (").substringBefore(", ").trim().takeIf { it.isNotEmpty() }
    }

    private fun usableDescription(text: String): Boolean {
        val lower = text.lowercase()
        return text.length >= 6 && "wikimedia" !in lower && "disambiguation" !in lower && !lower.startsWith("rijksmonument")
    }

    /** A spoken noun phrase for the protection of a monument, e.g. "a Rijksmonument, a listed national monument". */
    internal fun heritage(entity: WikidataEntity?, tags: Map<String, String>): String? {
        val designations = entity?.items?.get("P1435").orEmpty()
        val worldHeritage = designations.any { it == WORLD_HERITAGE } || tags["heritage"] == "1" || tags["heritage:operator"] == "whc"
        val rijksmonument = designations.any { it == RIJKSMONUMENT } || entity?.strings?.containsKey("P359") == true ||
            !tags["ref:rce"].isNullOrBlank() || tags["heritage:operator"] == "rce"
        return when {
            worldHeritage -> "part of a UNESCO World Heritage Site"
            rijksmonument -> "a Rijksmonument, a listed national monument"
            !tags["listed_status"].isNullOrBlank() -> tags["listed_status"]!!.let { status ->
                val spoken = status.replace("*", " star").trim()
                if (spoken.contains("listed", ignoreCase = true)) withArticle(spoken) else withArticle("$spoken listed building")
            }
            designations.isNotEmpty() -> "a listed monument"
            tags["heritage"] == "2" -> "a national monument"
            !tags["heritage"].isNullOrBlank() -> "a listed monument"
            else -> null
        }
    }

    /** One place per Wikidata item, and one per name within a short distance. */
    private fun dedupe(pois: List<Poi>): List<Poi> {
        val out = mutableListOf<Poi>()
        for (p in pois.sortedByDescending { it.facts?.substance ?: 0 }) {
            val duplicate = out.any { o ->
                (p.wikidataId != null && p.wikidataId == o.wikidataId) ||
                    (p.title.equals(o.title, ignoreCase = true) && GeoMath.distance(p.point, o.point) < SAME_PLACE_M)
            }
            if (!duplicate) out += p
        }
        return out
    }

    companion object {
        /** Wikidata properties whose values we need labels for: P31 instance of, P84 architect, P149 style, P138 named after, P466 occupant. */
        private val REFERENCES = listOf("P31", "P84", "P149", "P138", "P466")
        private const val RIJKSMONUMENT = "Q916333"
        private const val WORLD_HERITAGE = "Q9259"
        private const val SAME_PLACE_M = 150.0

        /** Kinds vague enough that Wikidata's "instance of" says it better ("weigh house"). */
        private val GENERIC = setOf(PlaceKind.BUILDING, PlaceKind.HISTORIC_BUILDING, PlaceKind.ATTRACTION)

        private fun itemId(tags: Map<String, String>): String? =
            tags["wikidata"]?.substringBefore(';')?.trim()?.takeIf { WikidataClient.isItemId(it) }

        /** Negative ids keep OpenStreetMap objects apart from Wikipedia page ids, and nodes from ways. */
        fun syntheticId(f: OsmFeature): Long {
            val type = when (f.type) {
                "node" -> 1
                "way" -> 2
                else -> 3
            }
            return -(f.id * 4 + type)
        }

        fun withArticle(phrase: String): String =
            if (phrase.first().lowercaseChar() in "aeiou") "an $phrase" else "a $phrase"
    }
}
