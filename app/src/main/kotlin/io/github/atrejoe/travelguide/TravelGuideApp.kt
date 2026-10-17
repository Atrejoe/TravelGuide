package io.github.atrejoe.travelguide

import android.app.Application
import io.github.atrejoe.travelguide.settings.SettingsRepository
import io.github.atrejoe.travelguide.voice.VoiceModelManager

class TravelGuideApp : Application() {
    lateinit var settings: SettingsRepository
        private set
    lateinit var voiceModels: VoiceModelManager
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        voiceModels = VoiceModelManager(this)
    }
}

val android.content.Context.app: TravelGuideApp get() = applicationContext as TravelGuideApp
