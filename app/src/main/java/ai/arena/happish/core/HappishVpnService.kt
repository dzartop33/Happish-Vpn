package ai.arena.happish.core

import ai.arena.happish.MainActivity
import ai.arena.happish.data.AppLog
import ai.arena.happish.data.AppStorage
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.util.Log

class HappishVpnService : VpnService() {
    private var core: SingBoxCoreAdapter? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopTunnel()
            ACTION_START -> startTunnel(intent.getStringExtra(EXTRA_CONFIG).orEmpty())
        }
        return START_STICKY
    }

    private fun startTunnel(configJson: String) {
        AppLog.i("VpnService: старт подключения")
        startForeground(NOTIFICATION_ID, notification("Подключение…"))
        runCatching {
            require(configJson.isNotBlank()) { "Пустой sing-box config" }

            core?.stop()
            val adapter = SingBoxCoreAdapter(this, configJson)
            if (!adapter.isAvailable()) {
                AppLog.e("libbox.aar не найден")
                error("libbox.aar не найден. Добавь app/libs/libbox.aar и пересобери APK.")
            }
            core = adapter
            AppLog.i("libbox найден, запускаю sing-box core")
            adapter.start()

            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("VPN активен"))
            AppStorage(this).saveVpnRunning(true)
            HappishWidgetProvider.updateAll(this)
            Log.i(TAG, "VPN started through sing-box/libbox")
            AppLog.i("VPN успешно запущен через sing-box/libbox")
        }.onFailure {
            Log.e(TAG, "VPN start failed", it)
            AppLog.e("Ошибка запуска VPN", it)
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, notification("Ошибка: ${it.message ?: "не удалось подключиться"}"))
            stopTunnel()
        }
    }

    private fun stopTunnel() {
        AppLog.i("VpnService: остановка подключения")
        AppStorage(this).saveVpnRunning(false)
        HappishWidgetProvider.updateAll(this)
        runCatching { core?.stop() }
        core = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    internal fun newVpnBuilder(): Builder = Builder()

    fun stopSelfFromCore() {
        AppStorage(this).saveVpnRunning(false)
        HappishWidgetProvider.updateAll(this)
        core = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        AppLog.w("Android отозвал VPN-разрешение")
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        runCatching { core?.stop() }
        core = null
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val channelId = "happish_vpn"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(channelId, "Happish VPN", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val openIntent = PendingIntent.getActivity(
            this,
            100,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stopIntent = PendingIntent.getService(
            this,
            101,
            Intent(this, HappishVpnService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, channelId)
            .setContentTitle("Happish VPN")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Отключить", stopIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "HappishVpnService"
        private const val NOTIFICATION_ID = 7
        const val ACTION_START = "ai.arena.happish.START"
        const val ACTION_STOP = "ai.arena.happish.STOP"
        const val EXTRA_CONFIG = "config"
    }
}
