package io.github.atrejoe.travelguide.service

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.atrejoe.travelguide.GuideStatus
import io.github.atrejoe.travelguide.ui.MainActivity

/** Quick Settings tile: pause or resume the guide from the system shade. */
class GuideTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        refresh()
    }

    override fun onClick() {
        super.onClick()
        if (GuideStatus.state.value.running) {
            startService(Intent(this, GuideService::class.java).setAction(GuideService.ACTION_TOGGLE))
        } else {
            openApp()
        }
        refresh()
    }

    private fun refresh() {
        val tile = qsTile ?: return
        val state = GuideStatus.state.value
        tile.state = if (state.running && !state.paused) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = when {
                !state.running -> "Off"
                state.paused -> "Paused"
                else -> "Guiding"
            }
        }
        tile.updateTile()
    }

    @Suppress("DEPRECATION")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            startActivityAndCollapse(intent)
        }
    }

    companion object {
        fun requestUpdate(context: Context) {
            try {
                requestListeningState(context, ComponentName(context, GuideTileService::class.java))
            } catch (e: RuntimeException) {
                // The tile is not added or the system refused; it refreshes when next shown anyway.
            }
        }
    }
}
