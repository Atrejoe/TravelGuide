package io.github.atrejoe.travelguide.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.atrejoe.travelguide.settings.AppLanguage
import io.github.atrejoe.travelguide.service.GuideMedia
import io.github.atrejoe.travelguide.update.Updates
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Screens and notifications in the app's language (issue #92).
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) playIfAsked(intent)
        setContent {
            TravelGuideTheme {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                if (showSettings) {
                    BackHandler { showSettings = false }
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    GuideScreen(onOpenSettings = { showSettings = true })
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // At most once a day, for installs from GitHub; see Updates.
        lifecycleScope.launch { Updates.check(applicationContext) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        playIfAsked(intent)
    }

    /** A voice request to play the guide (issue #94); without location, the screen asks for it. */
    private fun playIfAsked(intent: Intent?) {
        if (intent?.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) GuideMedia.play(this)
    }
}
