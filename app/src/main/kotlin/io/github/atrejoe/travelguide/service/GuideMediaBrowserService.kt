package io.github.atrejoe.travelguide.service

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.support.v4.media.MediaBrowserCompat.MediaItem
import android.support.v4.media.MediaDescriptionCompat
import androidx.media.MediaBrowserServiceCompat
import io.github.atrejoe.travelguide.GuideStatus
import io.github.atrejoe.travelguide.R
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Shows the guide in Android Auto as a media app (issue #94): play, pause and stop from the car,
 * the story being told on its screen, and a short list of recent stories to hear again.
 *
 * The list reveals where the listener has been, so only Android Auto, the car's media centre, the
 * system and this app may browse it; anyone else gets an empty root.
 */
class GuideMediaBrowserService : MediaBrowserServiceCompat() {
    private val scope = MainScope()

    override fun onCreate() {
        super.onCreate()
        sessionToken = GuideMedia.session(this).sessionToken
        scope.launch {
            GuideStatus.state
                .map { s -> s.running to s.history.take(MAX_STORIES).map { it.timeMillis } }
                .combine(CarStoryOffer.offered) { listed, offered -> listed to offered }
                .distinctUntilChanged()
                .drop(1)
                .collect { notifyChildrenChanged(ROOT) }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        if (!trusted(clientPackageName, clientUid)) return BrowserRoot(EMPTY_ROOT, null)
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
        }
        return BrowserRoot(ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaItem>>) {
        if (parentId != ROOT) {
            result.sendResult(mutableListOf())
            return
        }
        val state = GuideStatus.state.value
        val items = mutableListOf<MediaItem>()
        if (!state.running && CarStoryOffer.offered.value) {
            // Navigating in the car: offer stories along the route, and to remember the answer (issue #94).
            items += playable(GuideMedia.ID_START, getString(R.string.auto_offer_title), getString(R.string.auto_offer_subtitle))
            items += playable(GuideMedia.ID_ALWAYS, getString(R.string.auto_offer_always), getString(R.string.auto_offer_always_subtitle))
            items += playable(GuideMedia.ID_NEVER, getString(R.string.auto_offer_never), getString(R.string.auto_offer_never_subtitle))
        } else if (!state.running) {
            items += playable(GuideMedia.ID_START, getString(R.string.auto_start), getString(R.string.auto_start_subtitle))
        }
        state.history.take(MAX_STORIES).forEach { story ->
            items += playable(GuideMedia.storyId(story.timeMillis), story.title, getString(R.string.auto_play_again))
        }
        result.sendResult(items)
    }

    private fun playable(id: String, title: String, subtitle: String) = MediaItem(
        MediaDescriptionCompat.Builder().setMediaId(id).setTitle(title).setSubtitle(subtitle).build(),
        MediaItem.FLAG_PLAYABLE,
    )

    /** Android Auto, the car's media centre, the system or this app; see the class comment. */
    private fun trusted(packageName: String, uid: Int): Boolean {
        if (uid == Process.myUid() || uid == Process.SYSTEM_UID) return true
        if (packageName !in CAR_HOSTS) return false
        // Only the real host: preinstalled, or installed from Google Play.
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 || installer(packageName) == PLAY_STORE
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    @Suppress("DEPRECATION") // getInstallerPackageName before Android 11.
    private fun installer(packageName: String): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            packageManager.getInstallerPackageName(packageName)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private companion object {
        const val ROOT = "root"
        const val EMPTY_ROOT = "empty"
        const val MAX_STORIES = 20
        const val PLAY_STORE = "com.android.vending"

        /** Android Auto on the phone, and the media centre of cars running Android Automotive. */
        val CAR_HOSTS = setOf(
            "com.google.android.projection.gearhead",
            "com.android.car.media",
            "com.google.android.carassistant",
        )

        // Android Auto's content-style hints: show playable items as a list.
        const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        const val CONTENT_STYLE_LIST = 1
    }
}
