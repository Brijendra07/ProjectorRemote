package com.brijendra.projectorremote.service

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.brijendra.projectorremote.MainActivity
import com.brijendra.projectorremote.R

/** Home-screen widget: Back · Vol − · Play/Pause · Vol + · Home, plus tap the title to open the app. */
class RemoteWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val views = RemoteViews(ctx.packageName, R.layout.widget_remote).apply {
            setOnClickPendingIntent(R.id.w_back, KeyReceiver.pending(ctx, 4))
            setOnClickPendingIntent(R.id.w_vol_down, KeyReceiver.pending(ctx, 25))
            setOnClickPendingIntent(R.id.w_play, KeyReceiver.pending(ctx, 85))
            setOnClickPendingIntent(R.id.w_vol_up, KeyReceiver.pending(ctx, 24))
            setOnClickPendingIntent(R.id.w_home, KeyReceiver.pending(ctx, 3))
            setOnClickPendingIntent(
                R.id.w_open,
                PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
            )
        }
        ids.forEach { mgr.updateAppWidget(it, views) }
    }
}
