package io.github.atrejoe.travelguide.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import io.github.atrejoe.travelguide.core.enhance.ClaudeModel
import io.github.atrejoe.travelguide.core.enhance.LocalModel
import io.github.atrejoe.travelguide.core.enhance.LocalModels
import io.github.atrejoe.travelguide.core.lang.English
import io.github.atrejoe.travelguide.core.lang.StoryLanguage
import io.github.atrejoe.travelguide.core.lang.StoryLanguages
import io.github.atrejoe.travelguide.core.motion.TravelMode
import io.github.atrejoe.travelguide.voice.VoiceCatalog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Locale

data class AppSettings(
    /** The language of the stories and voices (issue #68), a [StoryLanguage] code. */
    val language: String = English.code,
    /** `neural:<id>` for an on-device Piper voice, `system:<name>` for an Android TTS voice. */
    val voiceId: String = VoiceCatalog.DEFAULT_VOICE_ID,
    /** Below 1 gives a deeper voice; 1 avoids the artefacts of pitch shifting. */
    val pitch: Float = 1.0f,
    /** Below 1 is slower, calmer narration. */
    val rate: Float = 0.95f,
    /** Null means detect the travel mode from speed. */
    val modeOverride: TravelMode? = null,
    /** Tell about the town, its buildings and institutions while walking (OpenStreetMap, Wikidata). */
    val walkingPlaces: Boolean = true,
    /** Also tell about places only the English or local Wikipedia knows, translated (issue #71). */
    val translatePlaces: Boolean = true,
    /** Who tells the stories (issue #7); the built-in storyteller by default. */
    val storyteller: Storyteller = Storyteller.BUILT_IN,
    /** A [ClaudeModel] id. */
    val claudeModel: String = ClaudeModel.DEFAULT.id,
    /** The most to spend on retelling per calendar month (UTC), in US dollars. */
    val monthlyBudgetUsd: Float = 2f,
    /** A [LocalModel] id, for the [Storyteller.ON_DEVICE] storyteller. */
    val localModel: String = LocalModels.DEFAULT.id,
)

/** Who tells the stories. The other two retell the built-in story, and fall back to it. */
enum class Storyteller {
    /** Template stories from Wikipedia text; no language model. */
    BUILT_IN,

    /** Gemini Nano, on the phone through Android AICore; free, but only on some phones. */
    GEMINI_NANO,

    /** Claude, with the listener's own Anthropic API key. */
    CLAUDE,

    /** An open model run by the app itself with llama.cpp (issue #51); free, on any 64-bit phone. */
    ON_DEVICE,
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val language = stringPreferencesKey("language")
        val voice = stringPreferencesKey("voice")
        val pitch = floatPreferencesKey("pitch")
        val rate = floatPreferencesKey("rate")
        val mode = stringPreferencesKey("mode_override")
        val walkingPlaces = booleanPreferencesKey("walking_places")
        val translatePlaces = booleanPreferencesKey("translate_places")
        val storyteller = stringPreferencesKey("storyteller")
        val claudeModel = stringPreferencesKey("claude_model")
        val monthlyBudget = floatPreferencesKey("monthly_budget_usd")
        val localModel = stringPreferencesKey("local_model")
        val version = intPreferencesKey("version")
    }

    /**
     * Upgrades settings stored by older versions: version 1 lowered the pitch by default (which
     * made voices sound metallic) and used voice ids that no longer exist.
     */
    suspend fun migrate() {
        context.dataStore.edit { p ->
            if ((p[Keys.version] ?: 1) < CURRENT_VERSION) {
                p[Keys.pitch] = AppSettings().pitch
                p[Keys.rate] = AppSettings().rate
                val voice = p[Keys.voice]
                if (voice != null && voice.startsWith(VoiceCatalog.NEURAL_PREFIX) && VoiceCatalog.neural(voice) == null) {
                    p.remove(Keys.voice)
                }
                p[Keys.version] = CURRENT_VERSION
            }
        }
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map(::fromPrefs)

    suspend fun current(): AppSettings = settings.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val next = transform(fromPrefs(p))
            p[Keys.language] = next.language
            p[Keys.voice] = next.voiceId
            p[Keys.pitch] = next.pitch
            p[Keys.rate] = next.rate
            if (next.modeOverride == null) p.remove(Keys.mode) else p[Keys.mode] = next.modeOverride.name
            p[Keys.walkingPlaces] = next.walkingPlaces
            p[Keys.translatePlaces] = next.translatePlaces
            p[Keys.storyteller] = next.storyteller.name
            p[Keys.claudeModel] = next.claudeModel
            p[Keys.monthlyBudget] = next.monthlyBudgetUsd
            p[Keys.localModel] = next.localModel
        }
    }

    private companion object {
        const val CURRENT_VERSION = 2

        /** The phone's language when stories can be told in it, else English. */
        fun phoneLanguage(): String = StoryLanguages.of(Locale.getDefault().language).code
    }

    private fun fromPrefs(p: Preferences): AppSettings {
        val defaults = AppSettings()
        // Earlier versions only told stories in English: keep it for those who chose a voice.
        val language = p[Keys.language] ?: if (p[Keys.voice] != null) English.code else phoneLanguage()
        return AppSettings(
            language = language,
            voiceId = p[Keys.voice] ?: VoiceCatalog.defaultVoiceId(language),
            pitch = p[Keys.pitch] ?: defaults.pitch,
            rate = p[Keys.rate] ?: defaults.rate,
            modeOverride = p[Keys.mode]?.let { name -> TravelMode.entries.firstOrNull { it.name == name } },
            walkingPlaces = p[Keys.walkingPlaces] ?: defaults.walkingPlaces,
            translatePlaces = p[Keys.translatePlaces] ?: defaults.translatePlaces,
            storyteller = p[Keys.storyteller]?.let { name -> Storyteller.entries.firstOrNull { it.name == name } } ?: defaults.storyteller,
            claudeModel = p[Keys.claudeModel] ?: defaults.claudeModel,
            monthlyBudgetUsd = p[Keys.monthlyBudget] ?: defaults.monthlyBudgetUsd,
            localModel = p[Keys.localModel] ?: defaults.localModel,
        )
    }
}
