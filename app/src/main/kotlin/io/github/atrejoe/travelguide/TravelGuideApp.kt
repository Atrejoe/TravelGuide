package io.github.atrejoe.travelguide

import android.app.Application
import io.github.atrejoe.travelguide.settings.SettingsRepository
import io.github.atrejoe.travelguide.voice.VoiceModelManager
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class TravelGuideApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var voiceModels: VoiceModelManager
        private set

    @OptIn(DelicateCoroutinesApi::class) // App-lifetime, one-off settings upgrade.
    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        voiceModels = VoiceModelManager(this)
        GlobalScope.launch { settings.migrate() }
    }
}

val android.content.Context.app: TravelGuideApp get() = applicationContext as TravelGuideApp
