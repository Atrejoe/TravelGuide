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
import io.github.atrejoe.travelguide.core.geo.GeoPoint
import io.github.atrejoe.travelguide.core.geo.LocationFix
import io.github.atrejoe.travelguide.core.guide.GuideEngine
import io.github.atrejoe.travelguide.core.rail.OverpassClient
import io.github.atrejoe.travelguide.core.route.PathSource
import io.github.atrejoe.travelguide.core.story.Narration
import io.github.atrejoe.travelguide.core.story.NarrationComposer
import io.github.atrejoe.travelguide.core.wiki.WikipediaClient
import io.github.atrejoe.travelguide.nav.RouteRepository
import io.github.atrejoe.travelguide.net.UrlConnectionFetcher
import io.github.atrejoe.travelguide.settings.AppSettings
import io.github.atrejoe.travelguide.voice.Narrator
import io.github.atrejoe.travelguide.voice.Narrators
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
    private var lastGpsFix = 0L

    private val locationListener = LocationListener { location -> onLocation(location) }

    override fun onCreate() {
        super.onCreate()
        val fetcher = UrlConnectionFetcher()
        val wikipedia = WikipediaClient(fetcher, lang = "en")
        engine = GuideEngine(wikipedia, articles = wikipedia, rails = OverpassClient(fetcher))
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
        focus.abandon()
        stopLocationUpdates()
        session.isActive = false
        engine.reset()
        GuideStatus.update {
            it.copy(
                running = false, paused = false, speaking = false, pauseReason = null, message = null,
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
            if (s.voiceId != settings.voiceId || s.pitch != settings.pitch || s.rate != settings.rate) {
                narratorDirty = true
            }
            settings = s
            engine.motion.modeOverride = s.modeOverride
        }
    }

    /** The narrator to use for the next sentence; never swapped while one is speaking. */
    private suspend fun currentNarrator(): Narrator {
        val existing = narrator
        if (existing != null && !narratorDirty) return existing
        existing?.release()
        narratorDirty = false
        val choice = Narrators.create(this, settings, app.voiceModels)
        GuideStatus.update { it.copy(voiceLabel = choice.narrator.label, voiceWarning = choice.warning) }
        return choice.narrator.also { narrator = it }
    }

    private suspend fun runGuide() {
        while (true) {
            delay(TICK_MILLIS)
            if (isPaused()) continue
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
            val narration = engine.nextNarration(now) ?: continue
            publishUpcoming(now)
            tell(narration)
            engine.onNarrationFinished(System.currentTimeMillis())
        }
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
        )
        GuideStatus.update { it.copy(current = item, speaking = true, history = (listOf(item) + it.history).take(50)) }
        updateSession()
        abortStory = false
        try {
            var index = 0
            while (index < narration.sentences.size && !abortStory) {
                awaitUnpaused()
                if (!focus.request()) {
                    delay(1_000)
                    continue
                }
                val completed = currentNarrator().speak(
                    narration.sentences[index],
                    upcoming = narration.sentences.getOrNull(index + 1),
                )
                // Interrupted by a pause: say the sentence again after resuming.
                if (completed || !isPaused()) index++
                // A short breath between sentences sounds more like a person telling a story.
                if (completed && index < narration.sentences.size) delay(SENTENCE_PAUSE_MILLIS)
            }
        } finally {
            focus.abandon()
            GuideStatus.update { it.copy(speaking = false) }
            updateSession()
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

        const val ACTION_START = "io.github.atrejoe.travelguide.START"
        const val ACTION_PAUSE = "io.github.atrejoe.travelguide.PAUSE"
        const val ACTION_RESUME = "io.github.atrejoe.travelguide.RESUME"
        const val ACTION_TOGGLE = "io.github.atrejoe.travelguide.TOGGLE"
        const val ACTION_STOP = "io.github.atrejoe.travelguide.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuideService::class.java).setAction(ACTION_START),
            )
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, GuideService::class.java).setAction(action))
        }
    }
}
