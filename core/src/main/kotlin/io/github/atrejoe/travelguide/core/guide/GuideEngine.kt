package io.github.atrejoe.travelguide.core.guide

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.motion.MotionState
import io.github.atrejoe.travelguide.core.motion.MotionTracker
import io.github.atrejoe.travelguide.core.route.PredictedPath
import io.github.atrejoe.travelguide.core.route.RouteExtrapolator
import io.github.atrejoe.travelguide.core.story.ArticleParser
import io.github.atrejoe.travelguide.core.story.DetailProfile
import io.github.atrejoe.travelguide.core.story.Narration
import io.github.atrejoe.travelguide.core.story.NarrationComposer
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiScorer
import io.github.atrejoe.travelguide.core.wiki.ArticleSource
import io.github.atrejoe.travelguide.core.wiki.PoiSource
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/**
 * The guide's brain, free of any Android dependency: feed it location fixes, let it refresh its
 * knowledge of the surroundings, and ask it what to tell next.
 *
 * Not thread-safe; drive it from a single coroutine.
 */
class GuideEngine(
    private val source: PoiSource,
    /** Full articles for richer stories; without it only the lead section is used. */
    private val articles: ArticleSource? = null,
    random: Random = Random.Default,
    private val refreshIntervalMillis: Long = 15 * 60_000,
    private val forgetDistanceM: Double = 30_000.0,
) {
    val motion = MotionTracker()
    val extrapolator = RouteExtrapolator()
    private val composer = NarrationComposer(random)

    var interestWeights: Map<Interest, Double> = emptyMap()

    private val known = LinkedHashMap<Long, Poi>()
    private val told = HashSet<Long>()
    private var lastFetchCenter: GeoPoint? = null
    private var lastFetchMillis: Long = 0
    private var lastFetchRadius: Int = 0
    private var lastNarrationEnd: Long? = null

    val knownPlaces: Int get() = known.size

    fun onLocation(fix: LocationFix): MotionState? = motion.onFix(fix)

    fun profile(): DetailProfile? = motion.state?.let { DetailProfile.forMode(it.mode) }

    fun predictedPath(): PredictedPath? {
        val state = motion.state ?: return null
        return extrapolator.predict(state, DetailProfile.forMode(state.mode).lookaheadSeconds)
    }

    /**
     * Fetches places around the area we are about to travel through when we have moved far enough
     * since the last fetch. Returns true when a fetch happened.
     */
    suspend fun refreshIfNeeded(nowMillis: Long): Boolean {
        val state = motion.state ?: return false
        val profile = DetailProfile.forMode(state.mode)
        val path = extrapolator.predict(state, profile.lookaheadSeconds)
        // Search around the middle of the predicted stretch so both near and upcoming places are covered.
        val center = pointAlong(path, minOf(path.lengthM / 2, profile.searchRadiusM * 0.6))

        val previous = lastFetchCenter
        val due = previous == null ||
            GeoMath.distance(previous, center) > lastFetchRadius * 0.5 ||
            profile.searchRadiusM > lastFetchRadius * 2 ||
            nowMillis - lastFetchMillis > refreshIntervalMillis
        if (!due) return false

        val places = source.nearby(center, profile.searchRadiusM)
        lastFetchCenter = center
        lastFetchMillis = nowMillis
        lastFetchRadius = profile.searchRadiusM
        places.forEach { known[it.pageId] = it }
        val here = state.fix.point
        known.values.removeAll { GeoMath.distance(here, it.point) > forgetDistanceM }
        return true
    }

    /**
     * The next story worth telling, or null when it is too soon after the last one or nothing
     * nearby is interesting enough. A returned story is marked as told.
     */
    suspend fun nextNarration(nowMillis: Long): Narration? {
        val state = motion.state ?: return null
        val profile = DetailProfile.forMode(state.mode)
        lastNarrationEnd?.let { if (nowMillis - it < profile.minGapSeconds * 1000L) return null }

        val path = extrapolator.predict(state, profile.lookaheadSeconds)
        val scorer = PoiScorer(profile, interestWeights)
        val best = known.values
            .asSequence()
            .filter { it.pageId !in told && it.extract.isNotBlank() }
            .mapNotNull { scorer.score(it, path) }
            .filter { it.score >= profile.minScore }
            .maxByOrNull { it.score }
            ?: return null

        told += best.poi.pageId
        val sections = try {
            articles?.article(best.poi)?.let(ArticleParser::parse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // The lead section from the search result is good enough.
        }
        return composer.compose(best, state, profile, sections)
    }

    /** Call when a story has finished playing so the next one waits for the configured gap. */
    fun onNarrationFinished(nowMillis: Long) {
        lastNarrationEnd = nowMillis
    }

    fun reset() {
        known.clear()
        told.clear()
        lastFetchCenter = null
        lastFetchMillis = 0
        lastFetchRadius = 0
        lastNarrationEnd = null
    }

    private fun pointAlong(path: PredictedPath, distanceM: Double): GeoPoint {
        var remaining = distanceM
        for ((a, b) in path.points.zipWithNext()) {
            val leg = GeoMath.distance(a, b)
            if (leg >= remaining) return GeoMath.destination(a, GeoMath.bearing(a, b), remaining)
            remaining -= leg
        }
        return path.end
    }
}
