package io.github.atrejoe.travelguide.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.voice.VoiceCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    /** `neural:<id>` for an on-device Piper voice, `system:<name>` for an Android TTS voice. */
    val voiceId: String = VoiceCatalog.DEFAULT_VOICE_ID,
    /** Below 1 gives a deeper voice. */
    val pitch: Float = 0.9f,
    /** Below 1 is slower, calmer narration. */
    val rate: Float = 0.9f,
    /** Null means detect the travel mode from speed. */
    val modeOverride: TravelMode? = null,
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val voice = stringPreferencesKey("voice")
        val pitch = floatPreferencesKey("pitch")
        val rate = floatPreferencesKey("rate")
        val mode = stringPreferencesKey("mode_override")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::fromPrefs)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val next = transform(fromPrefs(p))
            p[Keys.voice] = next.voiceId
            p[Keys.pitch] = next.pitch
            p[Keys.rate] = next.rate
            if (next.modeOverride == null) p.remove(Keys.mode) else p[Keys.mode] = next.modeOverride.name
        }
    }

    private fun fromPrefs(p: Preferences): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            voiceId = p[Keys.voice] ?: defaults.voiceId,
            pitch = p[Keys.pitch] ?: defaults.pitch,
            rate = p[Keys.rate] ?: defaults.rate,
            modeOverride = p[Keys.mode]?.let { name -> TravelMode.entries.firstOrNull { it.name == name } },
        )
    }
}
