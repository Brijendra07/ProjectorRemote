package com.brijendra.projectorremote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brijendra.projectorremote.MainActivity
import com.brijendra.projectorremote.R
import com.brijendra.projectorremote.adb.Connector
import com.brijendra.projectorremote.adb.RemoteController
import com.brijendra.projectorremote.media.MediaHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Keeps a remote in the notification shade / lock screen, and keeps the app
 * alive while the projector streams media from the phone.
 */
class RemoteService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MediaHub.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        val n = buildNotification(this)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(ID, n)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        MediaHub.stop()
        super.onDestroy()
    }

    companion object {
        private const val ID = 7
        private const val CHANNEL = "remote"
        const val ACTION_STOP = "com.brijendra.projectorremote.STOP"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, RemoteService::class.java))
            } catch (_: Throwable) {
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, RemoteService::class.java).setAction(ACTION_STOP))
            } catch (_: Throwable) {
            }
        }

        private fun buildNotification(ctx: Context): Notification {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Projector remote", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Remote controls in your notifications and lock screen"
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
            )
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val stop = PendingIntent.getService(
                ctx, 1, Intent(ctx, RemoteService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_n_remote)
                .setContentTitle("Projector remote")
                .setContentText("Tap to open · controls below")
                .setContentIntent(open)
                .setOngoing(true)
                .setSilent(true)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
                .addAction(R.drawable.ic_n_back, "Back", KeyReceiver.pending(ctx, 4))
                .addAction(R.drawable.ic_n_vol_down, "Vol −", KeyReceiver.pending(ctx, 25))
                .addAction(R.drawable.ic_n_play, "Play/Pause", KeyReceiver.pending(ctx, 85))
                .addAction(R.drawable.ic_n_vol_up, "Vol +", KeyReceiver.pending(ctx, 24))
                .addAction(R.drawable.ic_n_close, "Hide", stop)
                .setStyle(
                    androidx.media.app.NotificationCompat.MediaStyle()
                        .setShowActionsInCompactView(1, 2, 3)
                )
                .build()
        }
    }
}

/** Handles button presses from the notification and the home-screen widget. */
class KeyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = intent.getIntExtra(EXTRA_KEY, -1)
        if (code < 0) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (Connector.ensure(context)) RemoteController.get(context).key(code)
            } catch (_: Throwable) {
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_KEY = "key"

        fun pending(ctx: Context, code: Int): PendingIntent =
            PendingIntent.getBroadcast(
                ctx, 100 + code,
                Intent(ctx, KeyReceiver::class.java)
                    .setAction("com.brijendra.projectorremote.KEY_$code")
                    .putExtra(EXTRA_KEY, code),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
    }
}
