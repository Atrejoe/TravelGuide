package io.github.atrejoe.travelguide.service

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.core.content.ContextCompat
import io.github.atrejoe.travelguide.GuideStatus
import io.github.atrejoe.travelguide.GuideUiState
import io.github.atrejoe.travelguide.R
import io.github.atrejoe.travelguide.settings.CarNavigationStories
import io.github.atrejoe.travelguide.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * The guide's media session, shared by [GuideService] (notification, lock screen, headset buttons)
 * and [GuideMediaBrowserService], which Android Auto connects to (issue #94).
 *
 * It lives as long as the app rather than the guide, so the car can start the guide while it is
 * off. Its state follows [GuideStatus]; its commands go to [GuideService].
 */
object GuideMedia {
    private const val TAG = "GuideMedia"

    /** The browsable item that starts the guide. */
    const val ID_START = "start"

    /** Answers to the offer of stories while navigating (issue #94), as items and player buttons. */
    const val ID_ALWAYS = "offer:always"
    const val ID_NEVER = "offer:never"
    private const val ACTION_NOT_NOW = "offer:not_now"
    private const val STORY_PREFIX = "story:"

    fun storyId(timeMillis: Long) = "$STORY_PREFIX$timeMillis"

    private var session: MediaSessionCompat? = null

    /** The session, created on first use. Call on the main thread. */
    fun session(context: Context): MediaSessionCompat {
        session?.let { return it }
        val app = context.applicationContext
        val created = MediaSessionCompat(app, "TravelGuide").apply {
            setCallback(Commands(app))
            setSessionActivity(
                PendingIntent.getActivity(
                    app, 0,
                    Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    PendingIntent.FLAG_IMMUTABLE,
                ),
            )
        }
        session = created
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            combine(GuideStatus.state, CarStoryOffer.offered) { state, offered -> Shown.of(state, offered) }
                .distinctUntilChanged()
                .collect { publish(app, created, it) }
        }
        return created
    }

    /** The part of the guide's state the car shows. */
    private data class Shown(
        val running: Boolean,
        val paused: Boolean,
        val pauseReason: String?,
        val speaking: Boolean,
        val title: String?,
        val message: String?,
        val activity: String?,
        /** Offering stories while navigating in the car (issue #94). */
        val offer: Boolean,
    ) {
        companion object {
            fun of(s: GuideUiState, offered: Boolean) =
                Shown(s.running, s.paused, s.pauseReason, s.speaking, s.current?.title, s.message, s.activity, offered && !s.running)
        }
    }

    private fun publish(context: Context, session: MediaSessionCompat, shown: Shown) {
        val state = when {
            !shown.running -> PlaybackStateCompat.STATE_STOPPED
            shown.paused -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_PLAYING
        }
        var actions = PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH
        if (shown.running) {
            actions = actions or PlaybackStateCompat.ACTION_PAUSE or PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP
        }
        val playback = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, if (state == PlaybackStateCompat.STATE_PLAYING) 1f else 0f)
        if (shown.offer) {
            // "Play" says yes; these answer the offer and remember it.
            playback.addCustomAction(customAction(context, ID_ALWAYS, R.string.auto_offer_always_short, R.drawable.ic_always))
            playback.addCustomAction(customAction(context, ACTION_NOT_NOW, R.string.auto_offer_not_now, R.drawable.ic_close))
            playback.addCustomAction(customAction(context, ID_NEVER, R.string.auto_offer_never_short, R.drawable.ic_never))
        } else if (!shown.running && shown.message != null) {
            // Why the guide did not start, e.g. no location permission yet: the car shows it.
            playback.setErrorMessage(PlaybackStateCompat.ERROR_CODE_APP_ERROR, shown.message)
        }
        session.setPlaybackState(playback.build())

        val appName = context.getString(R.string.app_name)
        val title = when {
            shown.offer -> context.getString(R.string.auto_offer_title)
            shown.speaking && shown.title != null -> shown.title
            else -> appName
        }
        val subtitle = when {
            shown.offer -> context.getString(R.string.auto_offer_subtitle)
            !shown.running -> shown.message ?: context.getString(R.string.auto_press_play)
            shown.paused -> shown.pauseReason?.let { context.getString(R.string.auto_paused_because, it) } ?: context.getString(R.string.auto_paused)
            shown.speaking -> appName
            else -> shown.activity ?: shown.message ?: context.getString(R.string.auto_listening)
        }
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
                .build(),
        )
        session.isActive = shown.running || shown.offer
    }

    private fun customAction(context: Context, action: String, name: Int, icon: Int) =
        PlaybackStateCompat.CustomAction.Builder(action, context.getString(name), icon).build()

    private val running get() = GuideStatus.state.value.running

    /**
     * Starts the guide, or resumes it when paused: "play" from the car, a headset, or a voice
     * request such as "Hey Google, play Travel Guide". There is one guide to play, so any search
     * query plays it.
     */
    fun play(context: Context): Boolean =
        if (running) {
            send(context, GuideService.ACTION_RESUME)
            true
        } else {
            start(context, null)
        }

    /** Starts the guide, and plays [story] again first when given. False when it can't start. */
    private fun start(context: Context, story: Long?): Boolean {
        val located = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!located) {
            // Permissions are asked on the phone, never on the car screen.
            GuideStatus.update { it.copy(message = context.getString(R.string.auto_open_on_phone)) }
            return false
        }
        return try {
            GuideService.start(context, story)
            true
        } catch (e: IllegalStateException) {
            // Android may refuse to start a foreground service from the background.
            Log.w(TAG, "Cannot start the guide", e)
            GuideStatus.update { it.copy(message = context.getString(R.string.auto_open_on_phone)) }
            false
        }
    }

    private fun send(context: Context, action: String) {
        try {
            GuideService.send(context, action)
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Cannot reach the guide", e)
        }
    }

    /** Play, pause and stop from the car, a headset or the lock screen. */
    private class Commands(private val context: Context) : MediaSessionCompat.Callback() {
        override fun onPlay() {
            play(context)
        }

        override fun onPlayFromSearch(query: String?, extras: Bundle?) {
            play(context)
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            when (action) {
                ID_ALWAYS -> CarStoryOffer.answer(CarNavigationStories.ALWAYS)
                ID_NEVER -> CarStoryOffer.answer(CarNavigationStories.NEVER)
                ACTION_NOT_NOW -> CarStoryOffer.dismiss()
            }
        }

        override fun onPause() {
            if (running) send(context, GuideService.ACTION_PAUSE)
        }

        override fun onStop() {
            if (running) send(context, GuideService.ACTION_STOP)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            val story = mediaId?.takeIf { it.startsWith(STORY_PREFIX) }?.removePrefix(STORY_PREFIX)?.toLongOrNull()
            when {
                mediaId == ID_ALWAYS || mediaId == ID_NEVER -> onCustomAction(mediaId, extras)
                story == null -> onPlay()
                running -> GuideService.replay(context, story)
                else -> start(context, story)
            }
        }
    }
}
