package ai.arena.happish.core

import ai.arena.happish.MainActivity
import ai.arena.happish.R
import ai.arena.happish.data.AppLog
import ai.arena.happish.data.AppStorage
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import androidx.core.content.ContextCompat

class HappishWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateWidget(context, appWidgetManager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_TOGGLE) toggleVpn(context)
    }

    private fun toggleVpn(context: Context) {
        val storage = AppStorage(context)
        val state = storage.load()
        if (state.vpnRunning) {
            AppLog.i("Widget: STOP")
            context.startService(Intent(context, HappishVpnService::class.java).apply { action = HappishVpnService.ACTION_STOP })
            storage.saveVpnRunning(false)
            return
        }
        val server = state.servers.firstOrNull { it.id == state.selectedServerId } ?: state.servers.firstOrNull()
        if (server == null || VpnService.prepare(context) != null) {
            AppLog.w("Widget: открываю приложение для выбора сервера/VPN-разрешения")
            context.startActivity(Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
            return
        }
        val config = SingBoxConfigGenerator.generate(server, widgetDnsAddress(state.dnsPreset, state.customDns), state.routeMode)
        ContextCompat.startForegroundService(context, Intent(context, HappishVpnService::class.java).apply {
            action = HappishVpnService.ACTION_START
            putExtra(HappishVpnService.EXTRA_CONFIG, config)
        })
        storage.saveVpnRunning(true)
        AppLog.i("Widget: GO ${server.name}")
    }

    companion object {
        private const val ACTION_TOGGLE = "ai.arena.happish.WIDGET_TOGGLE"

        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, HappishWidgetProvider::class.java))
            ids.forEach { updateWidget(context, manager, it) }
        }

        fun updateWidget(context: Context, manager: AppWidgetManager, widgetId: Int) {
            val state = AppStorage(context).load()
            val views = RemoteViews(context.packageName, R.layout.happish_widget)
            views.setTextViewText(R.id.widget_status, if (state.vpnRunning) "Подключено" else "Отключено")
            views.setTextViewText(R.id.widget_toggle, if (state.vpnRunning) "STOP" else "GO")
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                widgetId,
                Intent(context, HappishWidgetProvider::class.java).apply { action = ACTION_TOGGLE },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            views.setOnClickPendingIntent(R.id.widget_toggle, pendingIntent)
            manager.updateAppWidget(widgetId, views)
        }

        private fun widgetDnsAddress(preset: String, customDns: String): String = when (preset) {
            "system" -> "local"
            "cloudflare" -> "tls://1.1.1.1"
            "adguard" -> "tls://94.140.14.14"
            "custom" -> customDns.ifBlank { "tls://8.8.8.8" }
            else -> "tls://8.8.8.8"
        }
    }
}
