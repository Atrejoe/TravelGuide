package io.github.atrejoe.travelguide.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import io.github.atrejoe.travelguide.GuideUiState
import io.github.atrejoe.travelguide.R
import io.github.atrejoe.travelguide.ui.MainActivity

object GuideNotification {
    const val ID = 1
    private const val CHANNEL = "guide"

    fun createChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.channel_description)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun build(context: Context, state: GuideUiState, session: MediaSessionCompat): Notification {
        val open = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val title = state.current?.title ?: context.getString(R.string.app_name)
        val text = when {
            state.paused -> state.pauseReason?.let { "Paused · $it" } ?: "Paused"
            state.speaking -> "Telling you about ${state.current?.title}"
            else -> state.message ?: "Listening to the road ahead"
        }
        val toggle = if (state.paused) {
            NotificationCompat.Action(R.drawable.ic_play, context.getString(R.string.action_resume), serviceIntent(context, GuideService.ACTION_RESUME))
        } else {
            NotificationCompat.Action(R.drawable.ic_pause, context.getString(R.string.action_pause), serviceIntent(context, GuideService.ACTION_PAUSE))
        }
        val stop = NotificationCompat.Action(R.drawable.ic_stop, context.getString(R.string.action_stop), serviceIntent(context, GuideService.ACTION_STOP))

        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(toggle)
            .addAction(stop)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .build()
    }

    private fun serviceIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getService(
            context, action.hashCode(),
            Intent(context, GuideService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
