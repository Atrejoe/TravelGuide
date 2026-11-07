package io.github.atrejoe.travelguide.core.lang

/**
 * Picks the language to use from the phone's languages (issue #92): its own languages in order
 * first, then the languages of its keyboards, and the first of those the app speaks wins.
 */
object LanguageChoice {
    /** The language codes the app's screens and stories come in. */
    val supported: List<String> = StoryLanguages.all.map { it.code }

    /**
     * The first of [preferred] (language tags such as "nl-BE" or "pt_BR") that is in [among],
     * else [fallback].
     */
    fun pick(preferred: List<String>, among: Collection<String> = supported, fallback: String = English.code): String =
        preferred.asSequence().map(::code).firstOrNull { it in among } ?: fallback

    /** "nl" for "nl-BE", "pt" for "pt_BR". */
    fun code(tag: String): String = tag.trim().substringBefore('-').substringBefore('_').lowercase()
}
