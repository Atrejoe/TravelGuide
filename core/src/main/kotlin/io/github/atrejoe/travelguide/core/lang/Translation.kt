package io.github.atrejoe.travelguide.core.lang

import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.wiki.PoiSource
import kotlinx.coroutines.CancellationException

/** Translates sentences, e.g. on the phone with ML Kit (issue #71). Throws when it can't. */
fun interface Translator {
    /** [texts] in language [from] (ISO 639-1), translated into [to], in the same order. */
    suspend fun translate(texts: List<String>, from: String, to: String): List<String>
}

/**
 * Places from the story language's Wikipedia, plus those only other Wikipedias know: English and
 * the local language (issue #71). A place found in the story language keeps its own article; the
 * others are told from their article and translated.
 *
 * Page ids of different Wikipedias can in theory be equal; the chance among the few dozen places
 * around is negligible, and the worst case is one place missed.
 */
class MultilingualPoiSource(
    private val primary: PoiSource,
    /** The other Wikipedias to search around a point; empty for none. */
    private val fallbacks: suspend (GeoPoint) -> List<PoiSource>,
) : PoiSource {
    override suspend fun nearby(center: GeoPoint, radiusM: Int): List<Poi> {
        val found = primary.nearby(center, radiusM).toMutableList()
        for (source in fallbacks(center)) {
            // The story language comes first; another Wikipedia being unreachable is no reason to stop.
            val extra = try {
                source.nearby(center, radiusM)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                continue
            }
            val items = found.mapNotNullTo(HashSet()) { it.wikidataId }
            val titles = found.mapTo(HashSet()) { it.title.lowercase() }
            found += extra.filter { p -> (p.wikidataId == null || p.wikidataId !in items) && p.title.lowercase() !in titles }
        }
        return found
    }
}

/** The language a country's Wikipedia is mostly written in, for the countries we can translate from. */
object CountryLanguages {
    private val languages = mapOf(
        "nl" to "nl", "be" to "nl", "sr" to "nl",
        "de" to "de", "at" to "de", "ch" to "de", "li" to "de",
        "fr" to "fr", "lu" to "fr", "mc" to "fr",
        "es" to "es", "mx" to "es", "ar" to "es", "co" to "es", "cl" to "es", "pe" to "es", "ve" to "es", "cu" to "es",
        "uy" to "es", "ec" to "es", "bo" to "es", "py" to "es", "cr" to "es", "pa" to "es", "do" to "es", "gt" to "es",
        "it" to "it", "sm" to "it", "va" to "it",
        "pt" to "pt", "br" to "pt", "ao" to "pt", "mz" to "pt",
        "pl" to "pl", "cz" to "cs", "sk" to "sk", "dk" to "da", "se" to "sv", "no" to "no", "fi" to "fi", "is" to "is",
        "gr" to "el", "tr" to "tr", "hu" to "hu", "ro" to "ro", "hr" to "hr", "si" to "sl", "bg" to "bg", "ee" to "et",
        "lv" to "lv", "lt" to "lt", "ua" to "uk", "ie" to "en", "gb" to "en", "us" to "en", "ca" to "en", "au" to "en",
        "nz" to "en", "jp" to "ja", "cn" to "zh", "kr" to "ko",
    )

    /** For an ISO 3166 country code such as "it"; null when unknown. */
    fun of(countryCode: String?): String? = countryCode?.lowercase()?.let(languages::get)
}
