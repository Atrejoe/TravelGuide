package io.github.atrejoe.travelguide.service

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import io.github.atrejoe.travelguide.voice.Narrators

/**
 * Plays nicely with other audio: music is ducked while the guide speaks, and the guide pauses
 * for phone calls and navigation prompts (they take transient focus) and resumes afterwards.
 */
class AudioFocus(context: Context, private val listener: Listener) {
    interface Listener {
        /** Another app needs the speaker for a while (call, navigation prompt): pause. */
        fun onFocusPaused()

        /** We got the speaker back. */
        fun onFocusResumed()

        /** Another app took over for good (e.g. started music): drop the current story. */
        fun onFocusLost()
    }

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(Narrators.audioAttributes)
        // We'd rather pause than mumble on underneath a navigation instruction.
        .setWillPauseWhenDucked(true)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_GAIN -> listener.onFocusResumed()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> listener.onFocusPaused()
                AudioManager.AUDIOFOCUS_LOSS -> listener.onFocusLost()
            }
        }
        .build()

    fun request(): Boolean =
        audioManager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED

    fun abandon() {
        audioManager.abandonAudioFocusRequest(request)
    }

    /** True while a phone or VoIP call is ringing or active. */
    fun isInCall(): Boolean = audioManager.mode in setOf(
        AudioManager.MODE_RINGTONE,
        AudioManager.MODE_IN_CALL,
        AudioManager.MODE_IN_COMMUNICATION,
    )
}
