package io.github.atrejoe.travelguide.service

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.atrejoe.travelguide.GuideStatus
import io.github.atrejoe.travelguide.MapPlace
import io.github.atrejoe.travelguide.StoryItem
import io.github.atrejoe.travelguide.UpcomingItem
import io.github.atrejoe.travelguide.app
import io.github.atrejoe.travelguide.core.enhance.ClaudeModel
import io.github.atrejoe.travelguide.core.enhance.ClaudeStoryEnhancer
import io.github.atrejoe.travelguide.core.enhance.LocalModels
import io.github.atrejoe.travelguide.core.enhance.LocalStoryEnhancer
import io.github.atrejoe.travelguide.core.enhance.StoryEnhancement
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.guide.GuideEngine
import io.github.atrejoe.travelguide.core.nav.NominatimClient
import io.github.atrejoe.travelguide.core.nav.OsrmClient
import io.github.atrejoe.travelguide.core.nav.TowardRouter
import io.github.atrejoe.travelguide.core.places.OsmPlaceSource
import io.github.atrejoe.travelguide.core.places.PlaceSource
import io.github.atrejoe.travelguide.core.places.TownLookup
import io.github.atrejoe.travelguide.core.rail.OverpassClient
import io.github.atrejoe.travelguide.core.road.RoadSource
import io.github.atrejoe.travelguide.core.route.PathSource
import io.github.atrejoe.travelguide.core.story.Narration
import io.github.atrejoe.travelguide.core.story.NarrationComposer
import io.github.atrejoe.travelguide.core.wiki.WikidataClient
import io.github.atrejoe.travelguide.core.wiki.WikipediaClient
import io.github.atrejoe.travelguide.nav.NavigationState
import io.github.atrejoe.travelguide.nav.RouteRepository
import io.github.atrejoe.travelguide.net.UrlConnectionFetcher
import io.github.atrejoe.travelguide.settings.AppSettings
import io.github.atrejoe.travelguide.settings.Storyteller
import io.github.atrejoe.travelguide.story.LlamaModel
import io.github.atrejoe.travelguide.story.LocalOnDevice
import io.github.atrejoe.travelguide.story.NanoStoryEnhancer
import io.github.atrejoe.travelguide.voice.Narrator
import io.github.atrejoe.travelguide.voice.Narrators
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/**
 * Runs the guide in the foreground: follows the location, picks stories and narrates them, and
 * exposes pause/resume through the notification, the media session and the Quick Settings tile.
 *
 * All guide logic runs on the main thread; network and synthesis hop to background dispatchers.
 */
class GuideService : LifecycleService(), AudioFocus.Listener {

    private lateinit var engine: GuideEngine
    private lateinit var session: MediaSessionCompat
    private lateinit var focus: AudioFocus
    private var narrator: Narrator? = null
    /** Set when voice settings or installed voices change; the narrator is rebuilt between sentences. */
    private var narratorDirty = true
    private var settings = AppSettings()
    private var guideJob: Job? = null

    private var userPaused = false
    private var focusPaused = false
    private var abortStory = false

    /** A story the user asked to hear again; played before anything else, then the guide goes on. */
    private var replayRequest: StoryItem? = null

    /** Wakes the guide loop early, e.g. for a replay while no story is being told. */
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var lastGpsFix = 0L

    private val locationListener = LocationListener { location -> onLocation(location) }

    private val fetcher = UrlConnectionFetcher()
    private val overpass = OverpassClient(fetcher)
    private val nominatim = NominatimClient(fetcher)

    /** Wikipedia and Wikidata in the story language (issue #68). */
    private inner class Sources(val lang: String) {
        val wikipedia = WikipediaClient(fetcher, lang = lang)
        private val wikidata = WikidataClient(fetcher, lang = lang)
        val buildings = OsmPlaceSource(overpass, wikidata, lang = lang)
        val towns = TownLookup(nominatim, wikidata, wikipedia, lang = lang)
    }

    private var sources = Sources(AppSettings().language)

    override fun onCreate() {
        super.onCreate()
        // The sources are looked up on every call, so a change of language takes effect at once.
        engine = GuideEngine(
            { center, radius -> sources.wikipedia.nearby(center, radius) },
            articles = { poi -> sources.wikipedia.article(poi) },
            rails = overpass,
            // Road networks can be large: parse them off the main thread.
            roads = RoadSource { center, major, minor, cycleways ->
                withContext(Dispatchers.Default) { overpass.roads(center, major, minor, cycleways) }
            },
            toward = TowardRouter(nominatim, OsrmClient(fetcher)),
            // Buildings in an old town centre make a sizeable answer: parse it off the main thread.
            places = PlaceSource { center, radius -> withContext(Dispatchers.Default) { sources.buildings.places(center, radius) } },
            towns = { point -> sources.towns.town(point) },
        )
        engine.onActivity = { what -> GuideStatus.update { it.copy(activity = what) } }
        focus = AudioFocus(this, this)
        GuideNotification.createChannel(this)
        session = MediaSessionCompat(this, "TravelGuide").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = setUserPaused(false)
                override fun onPause() = setUserPaused(true)
                override fun onStop() = stopGuide()
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val action = intent?.action
        if (action != ACTION_START && guideJob == null) {
            // A stale notification or tile action while the guide is off.
            stopSelf()
            return START_NOT_STICKY
        }
        when (action) {
            ACTION_START -> startGuide()
            ACTION_PAUSE -> setUserPaused(true)
            ACTION_RESUME -> setUserPaused(false)
            ACTION_TOGGLE -> setUserPaused(!userPaused)
            ACTION_STOP -> stopGuide()
            ACTION_REPLAY -> requestReplay(intent.getLongExtra(EXTRA_STORY, 0L))
        }
        return START_NOT_STICKY
    }

    private fun startGuide() {
        val types = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        } else {
            0
        }
        if (guideJob != null) return
        GuideStatus.update { it.copy(running = true, paused = false, pauseReason = null, message = "Waiting for location…") }
        try {
            ServiceCompat.startForeground(this, GuideNotification.ID, GuideNotification.build(this, GuideStatus.state.value, session), types)
        } catch (e: RuntimeException) {
            // Missing location permission or not allowed to start from the background.
            Log.e(TAG, "Cannot start in the foreground", e)
            GuideStatus.update { it.copy(running = false, message = "Could not start: ${e.message}") }
            stopSelf()
            return
        }

        session.isActive = true
        updateSession()
        startLocationUpdates()
        guideJob = lifecycleScope.launch {
            launch { watchSettings() }
            // Follow a route shared from a navigation app or imported from a file, when there is one.
            launch {
                RouteRepository.load(this@GuideService)
                RouteRepository.route.collect { route ->
                    engine.extrapolator.plannedRoute = route?.points
                    publishUpcoming(System.currentTimeMillis())
                }
            }
            // Follow a navigation app live through its turn-by-turn notifications (when allowed).
            launch {
                NavigationState.status.collect { status ->
                    val now = System.currentTimeMillis()
                    engine.onNavigation(status?.maneuver, status?.receivedAtMillis?.takeIf { it > 0 } ?: now)
                    publishUpcoming(now)
                }
            }
            // Pick up a neural voice once its download finishes.
            launch { app.voiceModels.installed.collect { narratorDirty = true } }
            launch {
                GuideStatus.state
                    .map { listOf(it.current?.title, it.paused, it.pauseReason, it.speaking, it.message) }
                    .distinctUntilChanged()
                    .collect { refreshNotification() }
            }
            runGuide()
        }
        GuideTileService.requestUpdate(this)
    }

    private fun stopGuide() {
        guideJob?.cancel()
        guideJob = null
        narrator?.release()
        narrator = null
        closeEnhancement()
        replayRequest = null
        focus.abandon()
        stopLocationUpdates()
        session.isActive = false
        engine.reset()
        GuideStatus.update {
            it.copy(
                running = false, paused = false, speaking = false, replaying = null, pauseReason = null, message = null,
                activity = null, waiting = null,
                upcoming = emptyList(), route = emptyList(), routeSource = PathSource.NONE,
            )
        }
        GuideTileService.requestUpdate(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (guideJob != null) stopGuide()
        session.release()
        super.onDestroy()
    }

    private suspend fun watchSettings() {
        app.settings.settings.distinctUntilChanged().collect { s ->
            if (s.language != sources.lang) {
                // Places and stories found so far are in the old language.
                sources = Sources(s.language)
                engine.forgetPlaces()
                narratorDirty = true
            }
            if (s.voiceId != settings.voiceId || s.language != settings.language) {
                narratorDirty = true
            } else if (s.pitch != settings.pitch || s.rate != settings.rate) {
                narrator?.adjust(s.pitch, s.rate)
            }
            settings = s
            engine.motion.modeOverride = s.modeOverride
            engine.walkingPlaces = s.walkingPlaces
        }
    }

    /** The narrator to use for the next sentence; never swapped while one is speaking. */
    private suspend fun currentNarrator(): Narrator {
        val existing = narrator
        if (existing != null && !narratorDirty) return existing
        existing?.drain()
        existing?.release()
        narratorDirty = false
        val choice = doing("Loading the voice…") { Narrators.create(this, settings, app.voiceModels) }
        GuideStatus.update { it.copy(voiceLabel = choice.narrator.label, voiceWarning = choice.warning) }
        return choice.narrator.also { narrator = it }
    }

    private suspend fun runGuide() {
        while (true) {
            withTimeoutOrNull(TICK_MILLIS) { wake.receive() }
            if (isPaused()) continue
            val story = replayRequest
            if (story != null) {
                replayRequest = null
                replay(story)
                continue
            }
            // Load (or reload) the voice ahead of the first story so problems show up early.
            if (narratorDirty) currentNarrator()
            val now = System.currentTimeMillis()
            try {
                if (engine.refreshIfNeeded(now)) {
                    GuideStatus.update { it.copy(knownPlaces = engine.knownPlaces, message = null) }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Could not fetch places", e)
                GuideStatus.update { it.copy(message = "Can't reach Wikipedia right now") }
                continue
            }
            publishUpcoming(now)
            val narration = engine.nextNarration(now)
            if (narration == null) {
                GuideStatus.update { it.copy(waiting = engine.waitingFor(now)) }
                // Between stories: fetch what the next one needs, so it starts without delay.
                engine.prefetchArticles()
                continue
            }
            publishUpcoming(now)
            tell(retold(narration))
            engine.onNarrationFinished(System.currentTimeMillis())
        }
    }

    private var enhancement: StoryEnhancement? = null
    private var enhancementConfig: List<Any>? = null

    /**
     * The story retold by Gemini Nano or Claude when the listener chose one (issue #7);
     * otherwise, or when anything goes wrong, the template story as it is.
     */
    private suspend fun retold(narration: Narration): Narration {
        val storyteller = when (settings.storyteller) {
            Storyteller.BUILT_IN -> return narration
            Storyteller.GEMINI_NANO -> "Gemini Nano"
            Storyteller.CLAUDE -> "Claude"
            Storyteller.ON_DEVICE -> LocalModels.byId(settings.localModel).name
        }
        val load: suspend () -> StoryEnhancement? = { withContext(Dispatchers.IO) { currentEnhancement() } }
        // Only the open model takes a while to load.
        val enhancement = (if (settings.storyteller == Storyteller.ON_DEVICE) doing("Loading $storyteller…", load) else load())
            ?: return narration
        val mode = engine.motion.state?.mode ?: return narration
        val story = doing("Retelling ${narration.poi.title} with $storyteller…") {
            withContext(Dispatchers.IO) { enhancement.enhance(narration, mode, engine.interestWeights) }
        }
        enhancement.lastError?.let { Log.w(TAG, "Could not retell ${narration.poi.title}", it) }
        Log.i(TAG, "Story of ${narration.poi.title}: ${enhancement.lastOutcome}")
        return story
    }

    /** Rebuilt when the storyteller, key, model or budget changes; null when it can't be used. */
    private suspend fun currentEnhancement(): StoryEnhancement? {
        val storyteller = settings.storyteller
        val key = if (storyteller == Storyteller.CLAUDE) app.apiKeys.get() ?: return null else null
        if (storyteller == Storyteller.GEMINI_NANO && !NanoStoryEnhancer.supported) return null
        val localModel = LocalModels.byId(settings.localModel)
        val localFile = if (storyteller == Storyteller.ON_DEVICE) app.localModels.file(localModel) ?: return null else null
        val config = listOf(storyteller, key.orEmpty(), settings.claudeModel, settings.monthlyBudgetUsd, localModel.id)
        if (config != enhancementConfig) {
            closeEnhancement()
            enhancementConfig = config
            enhancement = when (storyteller) {
                Storyteller.CLAUDE -> StoryEnhancement(
                    ClaudeStoryEnhancer(key!!, ClaudeModel.byId(settings.claudeModel)),
                    app.retoldStories,
                    settings.monthlyBudgetUsd.toDouble(),
                )
                // Free: no budget.
                Storyteller.GEMINI_NANO -> StoryEnhancement(NanoStoryEnhancer(), app.retoldStories, Double.MAX_VALUE)
                Storyteller.ON_DEVICE -> try {
                    val llama = LlamaModel.load(this, localFile!!, localModel.name)
                    StoryEnhancement(LocalOnDevice(LocalStoryEnhancer(llama, localModel), llama), app.retoldStories, Double.MAX_VALUE)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not load ${localModel.name}", e)
                    null
                }
                Storyteller.BUILT_IN -> null
            }
        }
        return enhancement
    }

    private fun closeEnhancement() {
        (enhancement?.enhancer as? AutoCloseable)?.close()
        enhancement = null
        enhancementConfig = null
    }

    private fun publishUpcoming(now: Long) {
        val items = engine.upcoming().map { story ->
            UpcomingItem(
                title = story.poi.title,
                where = NarrationComposer.label(story.relative),
                expectedAtMillis = story.etaSeconds?.let { now + it * 1000L },
                point = story.poi.point,
            )
        }
        val path = engine.predictedPath()
        GuideStatus.update {
            it.copy(
                upcoming = items,
                route = path?.points.orEmpty(),
                routeSource = path?.source ?: PathSource.NONE,
            )
        }
    }

    private suspend fun tell(narration: Narration) {
        val poi = narration.poi
        val item = StoryItem(
            poi.title, narration.text, poi.url, System.currentTimeMillis(),
            also = narration.companions.map { it.title },
            places = (listOf(poi) + narration.companions).map { MapPlace(it.title, it.point) },
            sentences = narration.sentences,
        )
        GuideStatus.update { it.copy(current = item, speaking = true, waiting = null, history = (listOf(item) + it.history).take(50)) }
        updateSession()
        abortStory = false
        try {
            speakStory(item)
        } finally {
            focus.abandon()
            GuideStatus.update { it.copy(speaking = false) }
            updateSession()
            warnIfVoiceTooSlow()
        }
    }

    /** Plays a story again while no other story is being told. */
    private suspend fun replay(item: StoryItem) {
        abortStory = false
        try {
            speakStory(item, replay = true)
        } finally {
            focus.abandon()
        }
    }

    /**
     * Speaks [item] piece by piece. A replay asked for meanwhile cuts in: it plays, and then this
     * story goes on from the piece that was cut off. Asking to replay this story starts it over.
     */
    private suspend fun speakStory(item: StoryItem, replay: Boolean = false) {
        val before = GuideStatus.state.value.replaying
        if (replay) GuideStatus.update { it.copy(replaying = item.timeMillis) }
        val first = currentNarrator()
        val pieces = Narrators.pieces(first, item.sentences)
        // Start rendering the whole story right away, also while waiting for audio focus.
        first.prepare(pieces.map { it.text })
        var prepared: Narrator? = first
        try {
            var index = 0
            while (index < pieces.size && !abortStory) {
                awaitUnpaused()
                val request = replayRequest
                if (request != null) {
                    replayRequest = null
                    if (request.timeMillis == item.timeMillis) {
                        index = 0
                    } else {
                        speakStory(request, replay = true)
                        prepared = null // The replay replaced what was rendered ahead.
                    }
                    continue
                }
                if (!focus.request()) {
                    delay(1_000)
                    continue
                }
                val narrator = currentNarrator()
                // Render the rest of the story ahead; again if the voice was changed meanwhile.
                if (narrator !== prepared) {
                    narrator.prepare(pieces.drop(index).map { it.text })
                    prepared = narrator
                }
                val text = pieces[index].text
                val completed = if (narrator.isPrepared(text)) narrator.speak(text) else doing("Preparing speech…") { narrator.speak(text) }
                // Cut off by a pause or by a replay: say the piece again afterwards.
                if (completed || !(isPaused() || replayRequest != null)) index++
                // A short breath between sentences sounds more like a person telling a story.
                if (completed && index < pieces.size && pieces[index - 1].endsSentence) narrator.silence(SENTENCE_PAUSE_MILLIS)
            }
            // The last piece may still be playing.
            if (!abortStory) narrator?.drain()
        } finally {
            prepared?.prepare(emptyList())
            if (replay) GuideStatus.update { it.copy(replaying = before) }
        }
    }

    /** Shows [what] as the guide's current activity while [block] runs (issue #65). */
    private suspend fun <T> doing(what: String, block: suspend () -> T): T {
        GuideStatus.update { it.copy(activity = what) }
        try {
            return block()
        } finally {
            GuideStatus.update { it.copy(activity = null) }
        }
    }

    /** Plays a told story again, cutting into the current one; see [speakStory]. */
    private fun requestReplay(storyTime: Long) {
        val state = GuideStatus.state.value
        val story = (listOfNotNull(state.current) + state.history).firstOrNull { it.timeMillis == storyTime } ?: return
        if (story.sentences.isEmpty()) return
        replayRequest = story
        if (userPaused) setUserPaused(false)
        if (state.speaking || state.replaying != null) narrator?.stop() else wake.trySend(Unit)
    }

    /** Tells the user when the chosen voice renders slower than it speaks on this phone. */
    private fun warnIfVoiceTooSlow() {
        val narrator = narrator ?: return
        val factor = narrator.realTimeFactor ?: return
        if (factor <= SLOW_VOICE_FACTOR) return
        GuideStatus.update {
            if (it.voiceWarning != null) it else it.copy(
                voiceWarning = "${narrator.label} renders slower than it speaks on this phone, so there are pauses " +
                    "between sentences. Piper voices (Settings) render several times faster than Kokoro ones.",
            )
        }
    }

    private suspend fun awaitUnpaused() {
        while (isPaused()) delay(500)
    }

    private fun isPaused(): Boolean {
        val inCall = focus.isInCall()
        val reason = when {
            inCall -> "phone call"
            focusPaused -> "another app is speaking"
            else -> null
        }
        val paused = userPaused || inCall || focusPaused
        val current = GuideStatus.state.value
        if (current.paused != paused || current.pauseReason != reason) {
            GuideStatus.update { it.copy(paused = paused, pauseReason = reason) }
            updateSession()
        }
        return paused
    }

    private fun setUserPaused(paused: Boolean) {
        userPaused = paused
        if (paused) narrator?.stop()
        isPaused()
        GuideTileService.requestUpdate(this)
    }

    override fun onFocusPaused() {
        focusPaused = true
        narrator?.stop()
        isPaused()
    }

    override fun onFocusResumed() {
        focusPaused = false
        isPaused()
    }

    override fun onFocusLost() {
        focusPaused = false
        abortStory = true
        narrator?.stop()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val manager = getSystemService(LocationManager::class.java)
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            if (provider !in manager.allProviders) continue
            try {
                manager.requestLocationUpdates(provider, 2_000L, 5f, locationListener, Looper.getMainLooper())
            } catch (e: SecurityException) {
                Log.w(TAG, "No location permission", e)
                GuideStatus.update { it.copy(message = "Location permission is needed") }
            }
        }
    }

    private fun stopLocationUpdates() {
        getSystemService(LocationManager::class.java).removeUpdates(locationListener)
    }

    private fun onLocation(location: Location) {
        if (location.provider == LocationManager.GPS_PROVIDER) {
            lastGpsFix = location.time
        } else if (location.time - lastGpsFix < 30_000) {
            return // Coarse network fixes only until GPS kicks in.
        }
        val fix = LocationFix(
            point = GeoPoint(location.latitude, location.longitude),
            timeMillis = location.time,
            speedMps = if (location.hasSpeed()) location.speed.toDouble() else null,
            bearingDeg = if (location.hasBearing()) location.bearing.toDouble() else null,
            accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null,
        )
        val motion = engine.onLocation(fix) ?: return
        GuideStatus.update {
            it.copy(
                mode = motion.mode,
                speedKmh = motion.speedMps * 3.6,
                position = motion.fix.point,
                onRail = engine.onRail,
                headingDeg = motion.headingDeg,
                message = it.message?.takeUnless { m -> m.startsWith("Waiting for location") },
            )
        }
    }

    private fun updateSession() {
        val state = GuideStatus.state.value
        val playback = if (state.paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP,
                )
                .setState(playback, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .build(),
        )
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, state.current?.title ?: "Travel Guide")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "Travel Guide")
                .build(),
        )
    }

    @SuppressLint("MissingPermission") // Without the permission the notification is simply not shown.
    private fun refreshNotification() {
        if (guideJob == null) return
        val notification = GuideNotification.build(this, GuideStatus.state.value, session)
        try {
            NotificationManagerCompat.from(this).notify(GuideNotification.ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot post notification", e)
        }
    }

    companion object {
        private const val TAG = "GuideService"
        private const val TICK_MILLIS = 3_000L
        private const val SENTENCE_PAUSE_MILLIS = 250L
        private const val SLOW_VOICE_FACTOR = 1.1

        const val ACTION_START = "io.github.atrejoe.travelguide.START"
        const val ACTION_PAUSE = "io.github.atrejoe.travelguide.PAUSE"
        const val ACTION_RESUME = "io.github.atrejoe.travelguide.RESUME"
        const val ACTION_TOGGLE = "io.github.atrejoe.travelguide.TOGGLE"
        const val ACTION_STOP = "io.github.atrejoe.travelguide.STOP"
        const val ACTION_REPLAY = "io.github.atrejoe.travelguide.REPLAY"
        const val EXTRA_STORY = "story"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuideService::class.java).setAction(ACTION_START),
            )
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, GuideService::class.java).setAction(action))
        }

        /** Plays the story told at [storyTimeMillis] again. */
        fun replay(context: Context, storyTimeMillis: Long) {
            context.startService(
                Intent(context, GuideService::class.java).setAction(ACTION_REPLAY).putExtra(EXTRA_STORY, storyTimeMillis),
            )
        }
    }
}
