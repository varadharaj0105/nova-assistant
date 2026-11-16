package com.varadha.nova

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews

/** Opens Nova already listening. */
fun listenIntent(c: Context) = Intent(c, MainActivity::class.java)
    .setAction(MainActivity.ACTION_LISTEN)
    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Home-screen widget: "Ask Nova" with a mic. */
class NovaWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, manager: AppWidgetManager, ids: IntArray) {
        val pi = PendingIntent.getActivity(
            c, 1, listenIntent(c),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val views = RemoteViews(c.packageName, R.layout.widget_mic).apply {
            setOnClickPendingIntent(R.id.widgetRoot, pi)
        }
        ids.forEach { manager.updateAppWidget(it, views) }
    }
}

/** Quick Settings tile: pull down, tap, talk. */
class NovaTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        qsTile?.apply {
            state = Tile.STATE_INACTIVE
            updateTile()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 2, listenIntent(this), PendingIntent.FLAG_IMMUTABLE)
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(listenIntent(this))
        }
    }
}
