package io.github.atrejoe.travelguide

import android.app.Application
import io.github.atrejoe.travelguide.core.voice.SpeechCache
import io.github.atrejoe.travelguide.nav.RouteRepository
import io.github.atrejoe.travelguide.settings.ApiKeyStore
import io.github.atrejoe.travelguide.settings.FileStoryStore
import io.github.atrejoe.travelguide.settings.SettingsRepository
import io.github.atrejoe.travelguide.story.LocalModelManager
import io.github.atrejoe.travelguide.voice.VoiceModelManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.File

class TravelGuideApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var voiceModels: VoiceModelManager
        private set
    lateinit var apiKeys: ApiKeyStore
        private set
    /** The listener's ElevenLabs key (issue #75). */
    lateinit var elevenLabsKeys: ApiKeyStore
        private set
    /** Speech fetched from ElevenLabs, so a replay costs nothing. */
    lateinit var speechCache: SpeechCache
        private set
    lateinit var retoldStories: FileStoryStore
        private set
    lateinit var localModels: LocalModelManager
        private set

    @OptIn(DelicateCoroutinesApi::class) // App-lifetime, one-off settings upgrade and route restore.
    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        CrashReporting.start(this)
        settings = SettingsRepository(this)
        voiceModels = VoiceModelManager(this)
        apiKeys = ApiKeyStore(this)
        elevenLabsKeys = ApiKeyStore(this, ApiKeyStore.ELEVENLABS)
        speechCache = SpeechCache(File(cacheDir, "speech"))
        retoldStories = FileStoryStore(this)
        localModels = LocalModelManager(this)
        GlobalScope.launch { settings.migrate() }
        GlobalScope.launch { RouteRepository.load(this@TravelGuideApp) }
    }
}

val android.content.Context.app: TravelGuideApp get() = applicationContext as TravelGuideApp
