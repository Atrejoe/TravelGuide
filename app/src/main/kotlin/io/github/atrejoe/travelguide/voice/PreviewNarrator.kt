package io.github.atrejoe.travelguide.voice

import android.content.Context
import io.github.atrejoe.travelguide.settings.AppSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Keeps the voice for the Settings preview loaded while Settings is open, so a preview starts
 * right away instead of loading the model first. Pitch and rate changes don't reload it.
 */
class PreviewNarrator(private val context: Context, private val models: VoiceModelManager) {
    private val mutex = Mutex()
    private var voiceId: String? = null
    private var choice: NarratorChoice? = null
    private var closed = false

    /** The narrator for [settings], loaded now unless the same voice is loaded already. */
    suspend fun get(settings: AppSettings): NarratorChoice = mutex.withLock {
        val loaded = choice
        // A fallback (e.g. the voice wasn't downloaded yet) is retried, it may work by now.
        if (loaded != null && voiceId == settings.voiceId && loaded.warning == null) {
            loaded.narrator.adjust(settings.pitch, settings.rate)
            return@withLock loaded
        }
        loaded?.narrator?.release()
        choice = null
        // Not cancellable: a voice loaded halfway would never be released.
        val fresh = withContext(NonCancellable) { Narrators.create(context, settings, models) }
        if (closed) fresh.narrator.release() else {
            voiceId = settings.voiceId
            choice = fresh
        }
        fresh
    }

    /** Stops any preview and frees the voice. */
    fun release() {
        closed = true
        choice?.narrator?.release()
        choice = null
    }
}
