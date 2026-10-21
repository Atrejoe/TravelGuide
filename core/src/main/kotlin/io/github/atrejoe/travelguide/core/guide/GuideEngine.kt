package io.github.atrejoe.travelguide.core.guide

import io.github.atrejoe.travelguide.core.geo.GeoMath
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.interest.Interest
import io.github.atrejoe.travelguide.core.interest.InterestClassifier
import io.github.atrejoe.travelguide.core.lang.StoryLanguage
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
import io.github.atrejoe.travelguide.core.lang.Translator
import io.github.atrejoe.travelguide.core.motion.MotionState
import io.github.atrejoe.travelguide.core.motion.MotionTracker
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.core.nav.Maneuver
import io.github.atrejoe.travelguide.core.nav.TowardRouting
import io.github.atrejoe.travelguide.core.places.PlaceSource
import io.github.atrejoe.travelguide.core.places.TownSource
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
import io.github.atrejoe.travelguide.core.story.ArticleSection
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
    /** Buildings and institutions (OpenStreetMap, Wikidata) to tell about while walking; optional. */
    private val places: PlaceSource? = null,
    /** The town we are walking in, to tell its story once per visit; optional. */
    private val towns: TownSource? = null,
    random: Random = Random.Default,
    private val refreshIntervalMillis: Long = 15 * 60_000,
    private val forgetDistanceM: Double = 30_000.0,
) {
    val motion = MotionTracker()
    val extrapolator = RouteExtrapolator()
    private val composer = NarrationComposer(random)

    var interestWeights: Map<Interest, Double> = InterestClassifier.defaultWeights

    /**
     * The language to tell in (issue #68); null tells every place in its own article's language.
     * Places found in another language are only told when there is a [translator].
     */
    var language: StoryLanguage? = null

    /** Translates what articles in another language say (issue #71); null to skip such places. */
    var translator: Translator? = null

    /** Whether to tell about buildings, institutions and the town itself while walking. */
    var walkingPlaces: Boolean = true

    /**
     * Told what the guide is fetching, e.g. "Reading about Grote Kerk…", and null when it is done
     * (issue #65).
     */
    var onActivity: (String?) -> Unit = {}

    private suspend fun <T> doing(what: String, block: suspend () -> T): T {
        onActivity(what)
        try {
            return block()
        } finally {
            onActivity(null)
        }
    }

    private val known = LinkedHashMap<Long, Poi>()
    private val told = HashSet<Long>()

    /** Wikidata items told about, so a place found by two sources is told only once. */
    private val toldItems = HashSet<String>()
    private var lastFetchCenter: GeoPoint? = null
    private var lastFetchMillis: Long = 0
    private var lastFetchRadius: Int = 0
    private var lastNarrationEnd: Long? = null

    /** When guiding started, until the first story; the quiet that widens the search counts from there. */
    private var quietSince: Long? = null

    /** Parsed articles by page id, fetched ahead of time; null when the place has no article. */
    private val articleCache = LinkedHashMap<Long, List<ArticleSection>?>()

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
            railNetwork = doing("Looking up railway lines…") { source.around(here, RAIL_RADIUS_M) }.also { extrapolator.railGraph = RailGraph(it) }
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
            val network = doing("Looking up the roads…") {
                source.roads(here, ROAD_MAJOR_RADIUS_M, if (minor) ROAD_MINOR_RADIUS_M else 0, cycleways = cycling)
            }
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
            doing("Planning the route towards $name…") { router.route(here, name, state.mode) }
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
        refreshTownIfNeeded(nowMillis)
        val placesFetched = refreshPlacesIfNeeded(nowMillis)
        val state = motion.state ?: return placesFetched
        if (quietSince == null) quietSince = nowMillis
        // Further afield when there has been nothing to tell for a while.
        val profile = DetailProfile.forMode(state.mode).widened(widening(nowMillis))
        val path = extrapolator.predict(state, profile.lookaheadSeconds)
        // Search around the middle of the predicted stretch so both near and upcoming places are covered.
        val center = pointAlong(path, minOf(path.lengthM / 2, profile.searchRadiusM * 0.6))

        val previous = lastFetchCenter
        val due = previous == null ||
            GeoMath.distance(previous, center) > lastFetchRadius * 0.5 ||
            profile.searchRadiusM > lastFetchRadius * 2 ||
            nowMillis - lastFetchMillis > refreshIntervalMillis
        if (!due) return placesFetched

        val articles = doing("Searching Wikipedia within ${radiusText(profile.searchRadiusM)}…") {
            source.nearby(center, profile.searchRadiusM)
        }
        lastFetchCenter = center
        lastFetchMillis = nowMillis
        lastFetchRadius = profile.searchRadiusM
        articles.forEach { known[it.pageId] = it }
        val here = state.fix.point
        known.values.removeAll { GeoMath.distance(here, it.point) > forgetDistanceM }
        dropDuplicates()
        return true
    }

    private var lastPlacesCenter: GeoPoint? = null
    private var lastPlacesMillis = 0L
    private var lastPlacesAttemptMillis = Long.MIN_VALUE / 2
    private var placesFailures = 0

    /**
     * Fetches the buildings and institutions around us while walking, when we have moved away
     * from the last fetch. Failures back off up to [PLACES_MAX_BACKOFF_MILLIS]: Overpass and
     * Wikidata are shared, free services.
     */
    private suspend fun refreshPlacesIfNeeded(nowMillis: Long): Boolean {
        val source = places ?: return false
        val state = motion.state ?: return false
        if (!walkingPlaces || state.mode != TravelMode.WALKING) return false
        val path = extrapolator.predict(state, DetailProfile.forMode(state.mode).lookaheadSeconds)
        val center = pointAlong(path, minOf(path.lengthM / 2, PLACES_AHEAD_M))
        val previous = lastPlacesCenter
        val fresh = previous != null && GeoMath.distance(previous, center) < PLACES_RADIUS_M * 0.5 &&
            nowMillis - lastPlacesMillis < PLACES_MAX_AGE_MILLIS
        if (fresh) return false
        val backoff = minOf(PLACES_RETRY_MILLIS shl (placesFailures - 1).coerceIn(0, 5), PLACES_MAX_BACKOFF_MILLIS)
        if (nowMillis - lastPlacesAttemptMillis < backoff) return false
        lastPlacesAttemptMillis = nowMillis
        val found = try {
            doing("Looking up buildings nearby…") { source.places(center, PLACES_RADIUS_M) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            placesFailures++
            return false
        }
        placesFailures = 0
        lastPlacesCenter = center
        lastPlacesMillis = nowMillis
        found.forEach { known[it.pageId] = it }
        // Buildings we walked away from are of no use any more.
        val here = state.fix.point
        known.values.removeAll { !it.isWikipediaPage && GeoMath.distance(here, it.point) > PLACES_FORGET_M }
        dropDuplicates()
        return true
    }

    /**
     * Forgets buildings found on OpenStreetMap that a Wikipedia article already covers: same
     * Wikidata item, same article, or the same name close by. The article tells the better story.
     */
    private fun dropDuplicates() {
        val articles = known.values.filter { it.isWikipediaPage }
        if (articles.isEmpty()) return
        val items = articles.mapNotNullTo(HashSet()) { it.wikidataId }
        val titles = articles.mapTo(HashSet()) { it.title.lowercase() }
        known.values.removeAll { p ->
            !p.isWikipediaPage && (
                (p.wikidataId != null && p.wikidataId in items) ||
                    p.wikiTitle?.lowercase() in titles ||
                    articles.any { a -> sameName(baseName(a.title), baseName(p.title)) && GeoMath.distance(a.point, p.point) < SAME_PLACE_M }
                )
        }
    }

    /** "Dom Tower of Utrecht (tower)" and "The Waag, Amsterdam" without their additions. */
    private fun baseName(title: String) = title.substringBefore(" (").substringBefore(", ").removePrefix("The ").trim()

    private var lastTownPoint: GeoPoint? = null
    private var lastTownAttemptMillis = Long.MIN_VALUE / 2
    private var townFailures = 0
    private var pendingTown: Poi? = null

    /** The town we are walking in, when known. */
    var currentTown: Poi? = null
        private set

    /**
     * Looks up which town we are walking in, after moving [TOWN_RECHECK_M] since the last look.
     * A town we haven't told about yet is told next (see [nextNarration]).
     */
    private suspend fun refreshTownIfNeeded(nowMillis: Long) {
        val source = towns ?: return
        val state = motion.state ?: return
        if (!walkingPlaces || state.mode != TravelMode.WALKING) return
        val here = state.fix.point
        val last = lastTownPoint
        if (last != null && GeoMath.distance(last, here) < TOWN_RECHECK_M) return
        val backoff = minOf(TOWN_RETRY_MILLIS shl (townFailures - 1).coerceIn(0, 5), PLACES_MAX_BACKOFF_MILLIS)
        if (nowMillis - lastTownAttemptMillis < backoff) return
        lastTownAttemptMillis = nowMillis
        val town = try {
            doing("Looking up which town this is…") { source.town(here) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            townFailures++
            return
        }
        townFailures = 0
        lastTownPoint = here
        if (town == null || town.pageId == currentTown?.pageId) return
        currentTown = town
        if (!isTold(town)) pendingTown = town
    }

    private fun isTold(poi: Poi) = poi.pageId in told || (poi.wikidataId != null && poi.wikidataId in toldItems)

    private fun markTold(poi: Poi) {
        told += poi.pageId
        poi.wikidataId?.let { toldItems += it }
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

        // Just walked into a town: welcome the listener with its story first.
        pendingTown?.let { town ->
            pendingTown = null
            if (!isTold(town)) return tellTown(town, state, profile)
        }

        val ranked = rank(state, profile, profile.minScore)
        val best = ranked.maxByOrNull { priority(it, profile) }
            ?: return widening(nowMillis).takeIf { it > 0 }?.let { tellAfield(state, profile.widened(it)) }
        markTold(best.scored.poi)

        // The lead section from the search result is good enough when the article can't be had.
        val poi = best.scored.poi
        val sections = if (poi.pageId in articleCache) articleCache[poi.pageId] else fetchArticle(poi)
        val articleText = sections?.joinToString(" ") { it.text } ?: best.scored.poi.extract
        val companions = companions(best, rank(state, profile, minScore = 0.0), articleText, state, profile)
        companions.forEach { markTold(it.poi) }
        return translated(composer.compose(best.scored, state, profile, sections, companions, storyLanguage = storyLanguage(poi)))
    }

    /**
     * A story from further afield (issue #64), when nothing nearby is worth one; null when there
     * is nothing there either.
     */
    private suspend fun tellAfield(state: MotionState, wide: DetailProfile): Narration? {
        val best = rank(state, wide, wide.minScore).maxByOrNull { priority(it, wide) } ?: return null
        val poi = best.scored.poi
        markTold(poi)
        val sections = if (poi.pageId in articleCache) articleCache[poi.pageId] else fetchArticle(poi)
        return translated(composer.compose(best.scored, state, wide, sections, afield = true, storyLanguage = storyLanguage(poi)))
    }

    /**
     * How many steps further afield to look (issue #64): one more for every [WIDEN_AFTER_MILLIS]
     * without a story once the next one was due, at most [MAX_WIDENING].
     */
    fun widening(nowMillis: Long): Int {
        val state = motion.state ?: return 0
        val since = lastNarrationEnd ?: quietSince ?: return 0
        val due = since + DetailProfile.forMode(state.mode).minGapSeconds * 1000L
        return ((nowMillis - due) / WIDEN_AFTER_MILLIS).toInt().coerceIn(0, MAX_WIDENING)
    }

    /**
     * What the guide waits for between stories, for the screen (issue #65): the gap after a
     * story, or how far it looks and when it will look further.
     */
    fun waitingFor(nowMillis: Long): String? {
        val state = motion.state ?: return null
        val profile = DetailProfile.forMode(state.mode)
        lastNarrationEnd?.let { end ->
            val left = end + profile.minGapSeconds * 1000L - nowMillis
            if (left > 0) return "Next story in ${durationText(left)}"
        }
        val level = widening(nowMillis)
        val since = lastNarrationEnd ?: quietSince ?: nowMillis
        val nextStep = since + profile.minGapSeconds * 1000L + (level + 1) * WIDEN_AFTER_MILLIS - nowMillis
        val radius = radiusText(profile.widened(level).searchRadiusM)
        return when {
            level == 0 -> "Nothing nearby worth a story yet; looking further afield in ${durationText(nextStep)}"
            level < MAX_WIDENING -> "Nothing nearby; looking further afield, within $radius. Wider in ${durationText(nextStep)}"
            else -> "Nothing nearby; looking further afield, within $radius"
        }
    }

    private fun storyLanguage(poi: Poi): StoryLanguage = language ?: StoryLanguages.of(poi.lang)

    /** A place in another language can only be told when it can be translated. */
    private fun canTell(poi: Poi): Boolean = language.let { it == null || it.code == poi.lang || translator != null }

    /**
     * [narration] with the sentences still in another language translated into the story's
     * (issue #71); null when that fails, so the place is skipped rather than told in the wrong
     * language.
     */
    private suspend fun translated(narration: Narration): Narration? {
        val target = narration.lang
        val foreign = narration.sentenceLangs.withIndex().filter { it.value != target }
        if (foreign.isEmpty()) return narration
        val translator = translator ?: return null
        val sentences = narration.sentences.toMutableList()
        try {
            doing("Translating ${narration.poi.title}…") {
                for ((from, items) in foreign.groupBy { it.value }) {
                    val indices = items.map { it.index }
                    val result = translator.translate(indices.map { narration.sentences[it] }, from, target)
                    check(result.size == indices.size) { "Translated ${result.size} of ${indices.size} sentences" }
                    indices.zip(result).forEach { (i, text) -> sentences[i] = text }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return narration.copy(sentences = sentences, sentenceLangs = sentences.map { target })
    }

    /** The story of the town we are in, history first; we are in it, wherever its centre is. */
    private suspend fun tellTown(town: Poi, state: MotionState, profile: DetailProfile): Narration? {
        markTold(town)
        val sections = if (town.pageId in articleCache) articleCache[town.pageId] else fetchArticle(town)
        val path = extrapolator.predict(state, profile.lookaheadSeconds)
        val scored = ScoredPoi(town, 1.0, path.locate(town.point), GeoMath.distance(state.fix.point, town.point))
        return translated(composer.compose(scored, state, profile, sections, relative = RelativePosition.Here, storyLanguage = storyLanguage(town)))
    }

    /**
     * Fetches the articles of the stories most likely to be told next, so a story can start
     * without waiting on the network. Call it regularly; it fetches at most one article per call.
     */
    suspend fun prefetchArticles(count: Int = PREFETCH_COUNT) {
        if (articles == null) return
        val state = motion.state ?: return
        val profile = DetailProfile.forMode(state.mode)
        val next = rank(state, profile, profile.minScore)
            .sortedByDescending { priority(it, profile) }
            .take(count)
            .firstOrNull { it.scored.poi.hasArticle && it.scored.poi.pageId !in articleCache }
            ?: return
        fetchArticle(next.scored.poi)
    }

    /** The parsed article, cached; null when unavailable. Network errors are not cached. */
    private suspend fun fetchArticle(poi: Poi): List<ArticleSection>? {
        val source = articles ?: return null
        if (!poi.hasArticle) return null
        val sections = try {
            doing("Reading about ${poi.title}…") { source.article(poi) }?.let { ArticleParser.parse(it, StoryLanguages.of(poi.lang)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        articleCache[poi.pageId] = sections
        if (articleCache.size > ARTICLE_CACHE_SIZE) articleCache.remove(articleCache.keys.first())
        return sections
    }

    /** How strongly a place asks to be told now: its interest, weighed by how soon we pass it. */
    private fun priority(ranked: Ranked, profile: DetailProfile): Double =
        ranked.scored.score * urgency(ranked.etaSeconds, profile)

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
            .filter { it.hasStory && !isTold(it) && canTell(it) }
            .mapNotNull { scorer.score(it, path) }
            .filter { it.score >= minScore }
            // A building without an article is only worth telling while you can see it.
            .filter { it.poi.isWikipediaPage || it.position.crossM <= PLACES_IN_SIGHT_M }
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

    /**
     * Forgets the places found and the stories told, e.g. after a change of language: they are
     * fetched again, in the new language. Travel, roads and navigation are kept.
     */
    fun forgetPlaces() {
        known.clear()
        told.clear()
        toldItems.clear()
        articleCache.clear()
        lastFetchCenter = null
        lastFetchMillis = 0
        lastFetchRadius = 0
        lastPlacesCenter = null
        lastPlacesAttemptMillis = Long.MIN_VALUE / 2
        lastTownPoint = null
        pendingTown = null
        currentTown = null
    }

    fun reset() {
        known.clear()
        told.clear()
        toldItems.clear()
        lastPlacesCenter = null
        lastPlacesMillis = 0
        lastPlacesAttemptMillis = Long.MIN_VALUE / 2
        placesFailures = 0
        lastTownPoint = null
        lastTownAttemptMillis = Long.MIN_VALUE / 2
        townFailures = 0
        pendingTown = null
        currentTown = null
        lastFetchCenter = null
        lastFetchMillis = 0
        lastFetchRadius = 0
        lastNarrationEnd = null
        quietSince = null
        articleCache.clear()
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

        /** Buildings within this radius around a point a little ahead of us. */
        const val PLACES_RADIUS_M = 500
        const val PLACES_AHEAD_M = 150.0
        const val PLACES_MAX_AGE_MILLIS = 30 * 60_000L

        /** Also the least time between two fetches of buildings. */
        const val PLACES_RETRY_MILLIS = 45_000L
        const val PLACES_MAX_BACKOFF_MILLIS = 15 * 60_000L
        const val PLACES_FORGET_M = 3_000.0
        const val PLACES_IN_SIGHT_M = 200.0
        const val SAME_PLACE_M = 200.0

        /** Look up the town again after walking this far (Nominatim allows one request per second). */
        const val TOWN_RECHECK_M = 400.0
        const val TOWN_RETRY_MILLIS = 60_000L

        /** Without a story for this long after the next one was due, look a step further afield. */
        const val WIDEN_AFTER_MILLIS = 2 * 60_000L
        const val MAX_WIDENING = 2

        const val PREFETCH_COUNT = 2
        const val ARTICLE_CACHE_SIZE = 20
    }

    private fun radiusText(meters: Int): String = when {
        meters < 1_000 -> "$meters m"
        meters % 1_000 == 0 -> "${meters / 1_000} km"
        else -> "${"%.1f".format(java.util.Locale.ROOT, meters / 1_000.0)} km"
    }

    /** "20 s" or "2 min", rounded up. */
    private fun durationText(millis: Long): String {
        val seconds = ((millis + 999) / 1_000).coerceAtLeast(1)
        return if (seconds < 60) "$seconds s" else "${(seconds + 59) / 60} min"
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
