package io.github.atrejoe.travelguide.core.enhance

import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.story.Narration
import io.github.atrejoe.travelguide.core.story.NarrationComposer
import io.github.atrejoe.travelguide.core.story.SentenceSplitter
import io.github.atrejoe.travelguide.core.story.TextCleaner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** What a language model gets to retell the story of a place. */
data class StoryRequest(
    val title: String,
    /** Wikipedia's short description, e.g. "church in Haarlem, Netherlands". */
    val description: String?,
    /** The article text to take the facts from. */
    val source: String,
    /** The sentence the guide opens with, saying where the place is; the story follows it. */
    val opener: String,
    val mode: TravelMode,
    /** Where the place is, e.g. "left · 300 m". */
    val where: String,
    /** The topics the listener cares most about, most important first. */
    val interests: List<Interest>,
    /** About how many sentences the story should have. */
    val sentences: Int,
    val lang: String,
)

/** A retold story and what it cost. */
data class RetoldStory(val text: String, val costUsd: Double)

/** Retells a story with a language model. Throws on any failure; [StoryEnhancement] falls back. */
interface StoryEnhancer {
    /** The most one [retell] can cost, so a call never runs over the budget. */
    val maxCostUsd: Double

    suspend fun retell(request: StoryRequest): RetoldStory
}

/** Keeps retold stories, so a place is never paid for twice, and what was spent per month. */
interface StoryStore {
    fun story(key: String): String?
    fun putStory(key: String, text: String)

    /** Dollars spent in [month], formatted like "2026-10". */
    fun spent(month: String): Double
    fun addSpent(month: String, usd: Double)
}

class MemoryStoryStore : StoryStore {
    private val stories = HashMap<String, String>()
    private val spending = HashMap<String, Double>()
    override fun story(key: String) = stories[key]
    override fun putStory(key: String, text: String) {
        stories[key] = text
    }
    override fun spent(month: String) = spending[month] ?: 0.0
    override fun addSpent(month: String, usd: Double) {
        spending[month] = spent(month) + usd
    }
}

/** How the last story was told; shown in the settings. */
enum class EnhanceOutcome {
    /** Retold by the language model just now. */
    RETOLD,

    /** Retold earlier; nothing paid. */
    CACHED,

    /** Template story: this month's budget is spent. */
    OVER_BUDGET,

    /** Template story: the call failed or took too long. */
    FAILED,

    /** Template story: not enough text about the place to retell. */
    SKIPPED,
}

/**
 * Optionally retells the template story (issue #7): the language model rewrites the part about
 * the place itself from its Wikipedia text. The opening sentence, which says where the place is
 * right now, and the bits about nearby places stay as they are, so a retold story can be kept
 * and told again later from another direction.
 *
 * Any failure, a timeout, or a spent budget leaves the template story; the guide never waits
 * longer than [timeoutMillis] for a story.
 */
class StoryEnhancement(
    private val enhancer: StoryEnhancer,
    private val store: StoryStore,
    private val monthlyBudgetUsd: Double,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    var lastOutcome: EnhanceOutcome? = null
        private set

    /** The last failure, for the log. */
    var lastError: Exception? = null
        private set

    fun spentThisMonth(): Double = store.spent(month())

    suspend fun enhance(narration: Narration, mode: TravelMode, interests: Map<Interest, Double>): Narration {
        val poi = narration.poi
        if (poi.extract.isBlank() || narration.bodySize == 0) return narration.also { lastOutcome = EnhanceOutcome.SKIPPED }
        val key = "${poi.lang}:${poi.pageId}"
        store.story(key)?.let { cached ->
            sentencesOf(cached)?.let {
                lastOutcome = EnhanceOutcome.CACHED
                return narration.withBody(it)
            }
        }
        val month = month()
        if (store.spent(month) + enhancer.maxCostUsd > monthlyBudgetUsd) {
            return narration.also { lastOutcome = EnhanceOutcome.OVER_BUDGET }
        }
        val body = narration.sentences.drop(1).take(narration.bodySize)
        val request = StoryRequest(
            title = poi.title,
            description = poi.description,
            // The lead section plus what the template picked from the rest of the article.
            source = (poi.extract + "\n\n" + body.joinToString(" ")).take(MAX_SOURCE_CHARS),
            opener = narration.sentences.first(),
            mode = mode,
            where = NarrationComposer.label(narration.relative),
            interests = interests.entries.filter { it.value > 0 }.sortedByDescending { it.value }.map { it.key },
            sentences = narration.bodySize.coerceAtLeast(MIN_SENTENCES),
            lang = poi.lang,
        )
        var failure: Exception? = null
        val retold = try {
            withTimeoutOrNull(timeoutMillis) { enhancer.retell(request) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
            null
        }
        lastError = failure
        if (retold == null) {
            // A call cut off by the timeout may still be billed: count the worst case.
            if (failure == null) store.addSpent(month, enhancer.maxCostUsd)
            return narration.also { lastOutcome = EnhanceOutcome.FAILED }
        }
        store.addSpent(month, retold.costUsd)
        val sentences = sentencesOf(retold.text) ?: return narration.also { lastOutcome = EnhanceOutcome.FAILED }
        store.putStory(key, sentences.joinToString(" "))
        lastOutcome = EnhanceOutcome.RETOLD
        return narration.withBody(sentences)
    }

    private fun sentencesOf(text: String): List<String>? =
        SentenceSplitter.split(TextCleaner.clean(text)).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }

    private fun Narration.withBody(body: List<String>): Narration =
        copy(sentences = listOf(sentences.first()) + body + sentences.drop(1 + bodySize), bodySize = body.size)

    private fun month(): String = YearMonth.from(Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC)).toString()

    companion object {
        const val DEFAULT_TIMEOUT_MILLIS = 20_000L
        private const val MAX_SOURCE_CHARS = 6_000
        private const val MIN_SENTENCES = 3
    }
}
