package io.github.atrejoe.travelguide.core.wiki

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** A time value of a Wikidata statement, e.g. "+1488-00-00T00:00:00Z" with precision 9 (year). */
data class WikidataTime(val time: String, val precision: Int)

/** A label, with the language it is in (which differs from the one asked for after a fallback). */
data class WikidataLabel(val text: String, val lang: String)

/** The parts of a Wikidata item we tell stories from. */
data class WikidataEntity(
    val id: String,
    val label: WikidataLabel? = null,
    /** Short description in the requested language only. */
    val description: String? = null,
    /** Title of the Wikipedia article in the requested language, if there is one. */
    val sitelink: String? = null,
    /** Statements pointing to other items, by property: "P84" to ["Q123"]. */
    val items: Map<String, List<String>> = emptyMap(),
    val times: Map<String, List<WikidataTime>> = emptyMap(),
    /** Statements with a string or identifier value, e.g. "P359" (Rijksmonument number). */
    val strings: Map<String, List<String>> = emptyMap(),
    /** False when only labels and sitelinks were fetched. */
    val hasClaims: Boolean = false,
)

/**
 * Reads items from Wikidata's public `wbgetentities` API (free, no key, data under CC0). Requests
 * are batched by [BATCH] ids and answers are cached, so walking through a town costs only a few
 * requests. Send a descriptive User-Agent from the [HttpFetcher], as Wikimedia asks.
 *
 * Not thread-safe; use it from one coroutine at a time.
 */
class WikidataClient(
    private val http: HttpFetcher,
    private val lang: String = "en",
    private val endpoint: String = "https://www.wikidata.org/w/api.php",
) {
    private val entityCache = lru<WikidataEntity?>(ENTITY_CACHE_SIZE)
    private val labelCache = lru<WikidataLabel?>(LABEL_CACHE_SIZE)

    /**
     * The items [ids] (e.g. "Q803"), from the cache where possible. Without [claims] only labels,
     * descriptions and sitelinks are fetched, which keeps the answer small for big items such as
     * cities. Unknown ids are left out of the result.
     */
    suspend fun entities(ids: Collection<String>, claims: Boolean = true): Map<String, WikidataEntity> {
        val wanted = ids.filter { isItemId(it) }.distinct()
        val missing = wanted.filter { id ->
            !entityCache.containsKey(id) || (claims && entityCache[id]?.hasClaims == false)
        }
        for (batch in missing.chunked(BATCH)) {
            val props = if (claims) "labels|descriptions|claims|sitelinks" else "labels|descriptions|sitelinks"
            val found = parseEntities(http.get(entitiesUrl(batch, props)), claims)
            batch.forEach { entityCache[it] = found[it] }
        }
        return wanted.mapNotNull { id -> entityCache[id]?.let { id to it } }.toMap()
    }

    /** Labels of the items [ids], in the requested language or else the best fallback. */
    suspend fun labels(ids: Collection<String>): Map<String, WikidataLabel> {
        val wanted = ids.filter { isItemId(it) }.distinct()
        val missing = wanted.filter { !labelCache.containsKey(it) && entityCache[it]?.label == null }
        for (batch in missing.chunked(BATCH)) {
            val found = parseEntities(http.get(entitiesUrl(batch, "labels")), claims = false)
            batch.forEach { labelCache[it] = found[it]?.label }
        }
        return wanted.mapNotNull { id -> (entityCache[id]?.label ?: labelCache[id])?.let { id to it } }.toMap()
    }

    fun entitiesUrl(ids: List<String>, props: String): String {
        val params = linkedMapOf(
            "action" to "wbgetentities",
            "format" to "json",
            "ids" to ids.joinToString("|"),
            "props" to props,
            "languages" to lang,
            "languagefallback" to "1",
            "sitefilter" to "${lang}wiki",
        )
        return endpoint + "?" + params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }

    fun parseEntities(body: String, claims: Boolean): Map<String, WikidataEntity> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyMap()
        val entities = root["entities"] as? JsonObject ?: return emptyMap()
        val out = HashMap<String, WikidataEntity>()
        for ((key, value) in entities) {
            val e = value as? JsonObject ?: continue
            if ("missing" in e) continue
            val id = e.string("id") ?: key
            val entity = parseEntity(id, e, claims)
            out[key] = entity
            out[id] = entity
        }
        return out
    }

    private fun parseEntity(id: String, e: JsonObject, claims: Boolean): WikidataEntity {
        val label = (e.obj("labels")?.obj(lang))?.let { l ->
            val text = l.string("value") ?: return@let null
            WikidataLabel(text, l.string("language") ?: lang)
        }
        val description = e.obj("descriptions")?.obj(lang)?.takeIf { it.string("language") == lang }?.string("value")
        val sitelink = e.obj("sitelinks")?.obj("${lang}wiki")?.string("title")
        val items = HashMap<String, List<String>>()
        val times = HashMap<String, List<WikidataTime>>()
        val strings = HashMap<String, List<String>>()
        e.obj("claims")?.forEach { (property, statements) ->
            val values = (statements as? JsonArray).orEmpty()
                .mapNotNull { it as? JsonObject }
                .filter { it.string("rank") != "deprecated" }
                // Preferred statements first: they are the current or best known value.
                .sortedBy { if (it.string("rank") == "preferred") 0 else 1 }
                .mapNotNull { it.obj("mainsnak")?.obj("datavalue") }
            val itemValues = values.mapNotNull { dv -> dv.obj("value")?.string("id")?.takeIf { dv.string("type") == "wikibase-entityid" } }
            val timeValues = values.mapNotNull { dv ->
                val v = dv.obj("value") ?: return@mapNotNull null
                if (dv.string("type") != "time") return@mapNotNull null
                WikidataTime(v.string("time") ?: return@mapNotNull null, v["precision"]?.jsonPrimitive?.intOrNull ?: 0)
            }
            val stringValues = values.mapNotNull { dv -> if (dv.string("type") == "string") dv["value"]?.jsonPrimitive?.contentOrNull else null }
            if (itemValues.isNotEmpty()) items[property] = itemValues
            if (timeValues.isNotEmpty()) times[property] = timeValues
            if (stringValues.isNotEmpty()) strings[property] = stringValues
        }
        return WikidataEntity(id, label, description, sitelink, items, times, strings, hasClaims = claims)
    }

    companion object {
        /** wbgetentities takes at most 50 ids per request. */
        const val BATCH = 50
        private const val ENTITY_CACHE_SIZE = 1_000
        private const val LABEL_CACHE_SIZE = 2_000

        private val json = Json { ignoreUnknownKeys = true }
        private val itemId = Regex("^Q[1-9][0-9]*$")

        fun isItemId(id: String): Boolean = itemId.matches(id)

        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
        private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun <V> lru(size: Int): LinkedHashMap<String, V> = object : LinkedHashMap<String, V>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = this.size > size
        }
    }
}
