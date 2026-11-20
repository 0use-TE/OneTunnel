// SPDX-License-Identifier: GPL-3.0-or-later
package dev.dsh.onetunnel

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import android.view.View

object TunnelSurfaces {
    private const val REFRESH = "dev.dsh.onetunnel.SURFACES_REFRESH"
    fun refresh(context: Context) {
        // Render in the UI process so widget appearance follows the same preference cache.
        runCatching { context.sendBroadcast(Intent(context, TunnelWidget::class.java).setAction(REFRESH)) }
    }
    fun isRefresh(intent: Intent) = intent.action == REFRESH
    fun label(status: TunnelStatus) = when (status.state) {
        "running" -> "已连接"
        "preparing", "starting" -> "连接中"
        "stopping" -> "断开中"
        "auth", "auth-starting" -> "认证中"
        "error" -> "连接失败"
        else -> "未连接"
    }
    fun intent(context: Context, status: TunnelStatus): Intent = when (ShortcutPolicy.action(status)) {
        ShortcutAction.DISCONNECT -> Intent(context, QuickConnectActivity::class.java).setAction(QuickConnectActivity.DISCONNECT)
        ShortcutAction.OPEN -> Intent(context, MainActivity::class.java)
        ShortcutAction.CONNECT -> Intent(context, QuickConnectActivity::class.java).setAction(QuickConnectActivity.CONNECT)
    }
    fun action(context: Context, status: TunnelStatus): PendingIntent {
        return PendingIntent.getActivity(context, 4101, intent(context, status),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}

class TunnelWidget : AppWidgetProvider() {
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (TunnelSurfaces.isRefresh(intent)) {
            val manager = AppWidgetManager.getInstance(context)
            onUpdate(context, manager, manager.getAppWidgetIds(ComponentName(context, TunnelWidget::class.java)))
        }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val status = StatusStore(context).read()
        val prefs = context.getSharedPreferences("appearance", Context.MODE_PRIVATE)
        val dark = when (prefs.getString("theme", "system")) {
            "dark" -> true; "light" -> false
            else -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        }
        val views = RemoteViews(context.packageName, R.layout.widget_tunnel).apply {
            setInt(R.id.widget_root, "setBackgroundResource", if (dark) R.drawable.widget_background_dark else R.drawable.widget_background)
            setTextColor(R.id.widget_title, Color.parseColor(if (dark) "#EDF2FA" else "#17212E"))
            setTextColor(R.id.widget_state, Color.parseColor(if (dark) "#AFBAC9" else "#626F80"))
            setTextViewText(R.id.widget_state, TunnelSurfaces.label(status))
            setInt(R.id.widget_power, "setBackgroundResource", if (status.state == "running") R.drawable.widget_power_on else R.drawable.widget_power_off)
            setInt(R.id.widget_power, "setColorFilter", Color.parseColor(if (status.state == "running") "#FFFFFF" else "#285EA8"))
            setBoolean(R.id.widget_power, "setEnabled", ShortcutPolicy.enabled(status))
            setContentDescription(R.id.widget_power, "OneTunnel · ${TunnelSurfaces.label(status)} · ${ShortcutPolicy.hint(status)}")
            setOnClickPendingIntent(R.id.widget_power, TunnelSurfaces.action(context, status))
            val open = PendingIntent.getActivity(context, 4102, Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            setOnClickPendingIntent(R.id.widget_info, open)
        }
        ids.forEach {
            val width = manager.getAppWidgetOptions(it).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180)
            views.setViewVisibility(R.id.widget_logo, if (width >= 240) View.VISIBLE else View.GONE)
            manager.updateAppWidget(it, views)
        }
        runCatching { TileService.requestListeningState(context, ComponentName(context, TunnelTileService::class.java)) }
    }
}

class TunnelTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        val status = StatusStore(this).read()
        qsTile?.apply {
            label = "OneTunnel"
            icon = Icon.createWithResource(this@TunnelTileService, R.drawable.ic_status)
            state = when {
                !ShortcutPolicy.enabled(status) -> Tile.STATE_UNAVAILABLE
                ShortcutPolicy.active(status) -> Tile.STATE_ACTIVE
                else -> Tile.STATE_INACTIVE
            }
            if (Build.VERSION.SDK_INT >= 29) subtitle = ShortcutPolicy.hint(status)
            contentDescription = "OneTunnel · ${TunnelSurfaces.label(status)} · ${ShortcutPolicy.hint(status)}"
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        val launch = Runnable {
            val status = StatusStore(this).read()
            if (!ShortcutPolicy.enabled(status)) return@Runnable
            val pending = TunnelSurfaces.action(this, status)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(pending)
            else {
                launchLegacy(TunnelSurfaces.intent(this, status))
            }
        }
        if (isLocked) unlockAndRun(launch) else launch.run()
    }
    // PendingIntent overload was introduced in API 34; this branch only serves API 26–33.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun launchLegacy(intent: Intent) { startActivityAndCollapse(intent) }
}
