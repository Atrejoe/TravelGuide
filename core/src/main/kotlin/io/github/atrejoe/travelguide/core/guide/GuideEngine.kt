package io.github.atrejoe.travelguide.core.guide

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.interest.InterestClassifier
import io.github.atrejoe.travelguide.core.motion.MotionState
import io.github.atrejoe.travelguide.core.motion.MotionTracker
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.nav.Maneuver
import io.github.atrejoe.travelguide.core.nav.TowardRouting
import io.github.atrejoe.travelguide.core.rail.RailDetector
import io.github.atrejoe.travelguide.core.rail.RailGraph
import io.github.atrejoe.travelguide.core.rail.RailNetwork
import io.github.atrejoe.travelguide.core.rail.RailSource
import io.github.atrejoe.travelguide.core.road.RoadGraph
import io.github.atrejoe.travelguide.core.road.RoadNetwork
import io.github.atrejoe.travelguide.core.road.RoadSource
import io.github.atrejoe.travelguide.core.route.LiveNavigation
import io.github.atrejoe.travelguide.core.route.PredictedPath
import io.github.atrejoe.travelguide.core.route.RouteExtrapolator
import io.github.atrejoe.travelguide.core.story.ArticleParser
import io.github.atrejoe.travelguide.core.story.DetailProfile
import io.github.atrejoe.travelguide.core.story.Narration
import io.github.atrejoe.travelguide.core.story.NarrationComposer
import io.github.atrejoe.travelguide.core.story.Poi
import io.github.atrejoe.travelguide.core.story.PoiScorer
import io.github.atrejoe.travelguide.core.story.RelativePosition
import io.github.atrejoe.travelguide.core.story.ScoredPoi
import io.github.atrejoe.travelguide.core.wiki.ArticleSource
import io.github.atrejoe.travelguide.core.wiki.PoiSource
import kotlinx.coroutines.CancellationException
import kotlin.random.Random

/** A story we expect to tell, for showing on a timeline. */
data class UpcomingStory(
    val poi: Poi,
    /** Seconds until we pass the place, or null when not moving. */
    val etaSeconds: Int?,
    val relative: RelativePosition,
)

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
    /** Railway lines nearby, to recognise train travel; without it only speed is used. */
    private val rails: RailSource? = null,
    /** Roads nearby, to follow the road and navigation instructions; without it we guess straight on. */
    private val roads: RoadSource? = null,
    /** Routes towards signposted places of navigation instructions; optional. */
    private val toward: TowardRouting? = null,
    random: Random = Random.Default,
    private val refreshIntervalMillis: Long = 15 * 60_000,
    private val forgetDistanceM: Double = 30_000.0,
) {
    val motion = MotionTracker()
    val extrapolator = RouteExtrapolator()
    private val composer = NarrationComposer(random)

    var interestWeights: Map<Interest, Double> = InterestClassifier.defaultWeights

    private val known = LinkedHashMap<Long, Poi>()
    private val told = HashSet<Long>()
    private var lastFetchCenter: GeoPoint? = null
    private var lastFetchMillis: Long = 0
    private var lastFetchRadius: Int = 0
    private var lastNarrationEnd: Long? = null

    val knownPlaces: Int get() = known.size

    private val railDetector = RailDetector()

    /** The railway lines around us, when known. */
    var railNetwork: RailNetwork? = null
        private set

    /** True while we appear to be travelling on a railway. */
    val onRail: Boolean get() = railDetector.onRail

    fun onLocation(fix: LocationFix): MotionState? {
        val state = motion.onFix(fix) ?: return null
        // An instruction that came in before our first position counts from here.
        extrapolator.navigation?.takeIf { it.origin == null }?.let { extrapolator.navigation = it.copy(origin = fix.point) }
        // Judged on this fix, applied from the next one: one fix of delay is fine for a mode.
        motion.onRail = railDetector.update(fix, state.speedMps, state.headingDeg, railNetwork?.takeIf { it.covers(fix.point) })
        return state
    }

    /**
     * Fetches railway lines around us when we move fast enough to be on a train and the last
     * fetch doesn't cover where we are. Failures are ignored: detection then falls back to speed.
     */
    private suspend fun refreshRailsIfNeeded(nowMillis: Long) {
        val source = rails ?: return
        val state = motion.state ?: return
        if (state.speedMps < RAIL_FETCH_MIN_SPEED_MPS && !railDetector.onRail) return
        val here = state.fix.point
        val current = railNetwork
        if (current != null && current.covers(here) && nowMillis - current.fetchedAtMillis < RAIL_MAX_AGE_MILLIS) return
        if (nowMillis - lastRailAttemptMillis < RAIL_RETRY_MILLIS) return
        lastRailAttemptMillis = nowMillis
        try {
            railNetwork = source.around(here, RAIL_RADIUS_M).also { extrapolator.railGraph = RailGraph(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Overpass is busy now and then; try again a little later.
        }
    }

    private var lastRailAttemptMillis = Long.MIN_VALUE / 2

    /** The roads around us, when known. */
    var roadNetwork: RoadNetwork? = null
        private set

    private var lastRoadAttemptMillis = Long.MIN_VALUE / 2
    private var roadFailures = 0
    private var navigationAtMillis = 0L
    private var lastTowardAttemptMillis = Long.MIN_VALUE / 2
    private var towardName: String? = null

    /** True while a navigation app's instruction steers the forecast. */
    val navigating: Boolean get() = extrapolator.navigation != null

    /**
     * The latest instruction of a navigation app, or null when navigation ended. Its distance is
     * taken to count from where we are now. An instruction not updated for a while is dropped
     * (see [refreshIfNeeded]).
     */
    fun onNavigation(maneuver: Maneuver?, nowMillis: Long) {
        if (maneuver == null || !maneuver.isUseful) {
            extrapolator.navigation = null
            towardName = null
            return
        }
        navigationAtMillis = nowMillis
        // Keep a route towards the signposted place while the instructions still point there, or
        // while they don't mention a place at all (e.g. "Continue for 5 km").
        val keep = extrapolator.navigation?.towardRoute?.takeIf { maneuver.toward == null || sameName(maneuver.toward, towardName) }
        if (keep == null) towardName = null
        extrapolator.navigation = LiveNavigation(maneuver, motion.state?.fix?.point, keep)
    }

    private fun sameName(a: String?, b: String?) = a != null && b != null && a.equals(b, ignoreCase = true)

    private fun expireNavigation(nowMillis: Long) {
        if (extrapolator.navigation != null && nowMillis - navigationAtMillis > NAVIGATION_MAX_AGE_MILLIS) {
            extrapolator.navigation = null
            towardName = null
        }
    }

    /**
     * Fetches the roads around us when driving, cycling or navigating, and the last fetch doesn't
     * cover where we are. Minor roads are only fetched where turns matter: while navigating at
     * city speeds, or by bike. Failures back off up to [ROAD_MAX_BACKOFF_MILLIS].
     */
    private suspend fun refreshRoadsIfNeeded(nowMillis: Long) {
        val source = roads ?: return
        val state = motion.state ?: return
        val navigating = extrapolator.navigation != null
        val roadMode = state.mode == TravelMode.DRIVING || state.mode == TravelMode.CYCLING
        if (!roadMode && !(navigating && state.mode == TravelMode.WALKING)) return
        val cycling = state.mode == TravelMode.CYCLING
        val minor = cycling || (navigating && state.speedMps < MINOR_ROADS_MAX_SPEED_MPS)
        val here = state.fix.point
        val current = roadNetwork
        val fresh = current != null && current.covers(here) && nowMillis - current.fetchedAtMillis < ROAD_MAX_AGE_MILLIS &&
            (current.hasMinorRoads || !minor) && (current.cycleways || !cycling)
        if (fresh) return
        val backoff = minOf(ROAD_RETRY_MILLIS shl (roadFailures - 1).coerceIn(0, 5), ROAD_MAX_BACKOFF_MILLIS)
        if (nowMillis - lastRoadAttemptMillis < backoff) return
        lastRoadAttemptMillis = nowMillis
        try {
            val network = source.roads(here, ROAD_MAJOR_RADIUS_M, if (minor) ROAD_MINOR_RADIUS_M else 0, cycleways = cycling)
            roadNetwork = network
            extrapolator.roadGraph = RoadGraph(network)
            roadFailures = 0
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            roadFailures++
        }
    }

    /**
     * Computes a route towards the place a navigation instruction points to ("towards Utrecht"),
     * at most once a minute, and only when we're not already on such a route.
     */
    private suspend fun refreshTowardIfNeeded(nowMillis: Long) {
        val router = toward ?: return
        val nav = extrapolator.navigation ?: return
        val state = motion.state ?: return
        val name = nav.maneuver.toward ?: return
        if (state.mode == TravelMode.TRAIN || state.mode == TravelMode.FLYING) return
        val here = state.fix.point
        val route = nav.towardRoute
        val onRoute = route != null && sameName(name, towardName) && PredictedPath(route).locate(here).crossM < TOWARD_OFF_ROUTE_M
        if (onRoute) return
        if (nowMillis - lastTowardAttemptMillis < TOWARD_RETRY_MILLIS) return
        lastTowardAttemptMillis = nowMillis
        val computed = try {
            router.route(here, name, state.mode)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // The instruction may have changed meanwhile.
        val latest = extrapolator.navigation ?: return
        if (!sameName(latest.maneuver.toward, name)) return
        towardName = name
        extrapolator.navigation = latest.copy(towardRoute = computed)
    }

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
        expireNavigation(nowMillis)
        refreshRailsIfNeeded(nowMillis)
        refreshRoadsIfNeeded(nowMillis)
        refreshTowardIfNeeded(nowMillis)
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
     * nearby is interesting enough. A returned story (and the places woven into it) are marked as
     * told.
     *
     * Places we will reach soon are preferred over equally interesting ones further ahead, and
     * places just beyond the main one, or mentioned in its article, are combined into one story.
     */
    suspend fun nextNarration(nowMillis: Long): Narration? {
        val state = motion.state ?: return null
        val profile = DetailProfile.forMode(state.mode)
        lastNarrationEnd?.let { if (nowMillis - it < profile.minGapSeconds * 1000L) return null }

        val ranked = rank(state, profile, profile.minScore)
        val best = ranked.maxByOrNull { it.scored.score * urgency(it.etaSeconds, profile) } ?: return null
        told += best.scored.poi.pageId

        val sections = try {
            articles?.article(best.scored.poi)?.let(ArticleParser::parse)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // The lead section from the search result is good enough.
        }
        val articleText = sections?.joinToString(" ") { it.text } ?: best.scored.poi.extract
        val companions = companions(best, rank(state, profile, minScore = 0.0), articleText, state, profile)
        told += companions.map { it.poi.pageId }
        return composer.compose(best.scored, state, profile, sections, companions)
    }

    /**
     * Stories we expect to tell soon, in the order we will reach them, for the timeline on screen.
     * The arrival time is a prediction from the current speed and route.
     */
    fun upcoming(limit: Int = 5): List<UpcomingStory> {
        val state = motion.state ?: return emptyList()
        val profile = DetailProfile.forMode(state.mode)
        return rank(state, profile, profile.minScore)
            .sortedWith(compareBy<Ranked>({ it.etaSeconds ?: Int.MAX_VALUE }, { -it.scored.score }))
            .take(limit)
            .map { UpcomingStory(it.scored.poi, it.etaSeconds, composer.relativePosition(it.scored, state)) }
    }

    internal data class Ranked(val scored: ScoredPoi, val etaSeconds: Int?)

    /** Untold places worth a story, with when we will pass them. */
    private fun rank(state: MotionState, profile: DetailProfile, minScore: Double): List<Ranked> {
        val path = extrapolator.predict(state, profile.lookaheadSeconds)
        val scorer = PoiScorer(profile, interestWeights)
        val moving = state.speedMps > 0.5 && path.points.size > 1
        return known.values
            .asSequence()
            .filter { it.pageId !in told && it.extract.isNotBlank() }
            .mapNotNull { scorer.score(it, path) }
            .filter { it.score >= minScore }
            .map { scored ->
                val eta = if (moving) (scored.position.alongM.coerceAtLeast(0.0) / state.speedMps).toInt() else null
                Ranked(scored, eta)
            }
            .toList()
    }

    /** 1 for places we reach within a minute, down to 0.5 for those at the end of the look-ahead. */
    private fun urgency(etaSeconds: Int?, profile: DetailProfile): Double {
        if (etaSeconds == null || etaSeconds <= 60) return 1.0
        val horizon = (profile.lookaheadSeconds * 1.5).coerceAtLeast(120.0)
        return (1.0 - 0.5 * (etaSeconds - 60) / horizon).coerceIn(0.5, 1.0)
    }

    /**
     * Up to two places to weave into the story: ones the article mentions, or ones we pass right
     * after the main place.
     */
    private fun companions(
        main: Ranked,
        ranked: List<Ranked>,
        articleText: String,
        state: MotionState,
        profile: DetailProfile,
    ): List<ScoredPoi> {
        // What we pass in about two minutes, but at least what you can see from the main place.
        val window = maxOf(state.speedMps * 120, profile.visibilityM * 2)
        val mainAlong = main.scored.position.alongM
        return ranked
            .asSequence()
            .filter { it.scored.poi.pageId != main.scored.poi.pageId }
            .mapNotNull { other ->
                val distance = GeoMath.distance(main.scored.poi.point, other.scored.poi.point)
                val gap = other.scored.position.alongM - mainAlong
                val mentioned = distance < maxOf(window * 4, 5_000.0) &&
                    NarrationComposer.mentions(articleText, other.scored.poi.title)
                val near = gap in -window * 0.3..window && distance < window * 1.5 &&
                    other.scored.score >= profile.minScore
                when {
                    mentioned -> other to other.scored.score + 1.0
                    near -> other to other.scored.score
                    else -> null
                }
            }
            .sortedByDescending { it.second }
            .take(MAX_COMPANIONS)
            .map { it.first.scored }
            .toList()
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
        railNetwork = null
        extrapolator.railGraph = null
        roadNetwork = null
        extrapolator.roadGraph = null
        extrapolator.navigation = null
        towardName = null
        roadFailures = 0
        lastRoadAttemptMillis = Long.MIN_VALUE / 2
        lastTowardAttemptMillis = Long.MIN_VALUE / 2
        railDetector.reset()
        motion.onRail = false
    }

    private companion object {
        const val MAX_COMPANIONS = 2
        const val RAIL_RADIUS_M = 3_000
        const val RAIL_FETCH_MIN_SPEED_MPS = 8.0
        const val RAIL_MAX_AGE_MILLIS = 30 * 60_000L
        const val RAIL_RETRY_MILLIS = 60_000L

        const val ROAD_MAJOR_RADIUS_M = 5_000
        const val ROAD_MINOR_RADIUS_M = 1_000

        /** Above about 70 km/h side streets don't matter for the next turns. */
        const val MINOR_ROADS_MAX_SPEED_MPS = 20.0
        const val ROAD_MAX_AGE_MILLIS = 30 * 60_000L

        /** Also the least time between two road fetches, to go easy on Overpass. */
        const val ROAD_RETRY_MILLIS = 45_000L
        const val ROAD_MAX_BACKOFF_MILLIS = 15 * 60_000L

        /** A navigation instruction not updated for this long is considered stale. */
        const val NAVIGATION_MAX_AGE_MILLIS = 2 * 60_000L
        const val TOWARD_RETRY_MILLIS = 60_000L
        const val TOWARD_OFF_ROUTE_M = 60.0
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
