package ai.arena.happish.core

import ai.arena.happish.MainActivity
import ai.arena.happish.data.AppLog
import ai.arena.happish.data.AppStorage
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.core.content.ContextCompat

class HappishTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val storage = AppStorage(this)
        val state = storage.load()
        if (state.vpnRunning) {
            AppLog.i("Quick Settings Tile: STOP")
            startService(Intent(this, HappishVpnService::class.java).apply {
                action = HappishVpnService.ACTION_STOP
            })
            storage.saveVpnRunning(false)
            updateTile(false)
            return
        }

        val selectedServer = state.servers.firstOrNull { it.id == state.selectedServerId } ?: state.servers.firstOrNull()
        if (selectedServer == null) {
            AppLog.w("Quick Settings Tile: сервер не выбран")
            openMainActivity()
            return
        }

        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            AppLog.w("Quick Settings Tile: нужно VPN-разрешение, открываю приложение")
            openMainActivity()
            return
        }

        AppLog.i("Quick Settings Tile: GO ${selectedServer.name}")
        val dnsAddress = tileDnsAddress(state.dnsPreset, state.customDns)
        val config = SingBoxConfigGenerator.generate(selectedServer, dnsAddress, state.routeMode)
        ContextCompat.startForegroundService(this, Intent(this, HappishVpnService::class.java).apply {
            action = HappishVpnService.ACTION_START
            putExtra(HappishVpnService.EXTRA_CONFIG, config)
        })
        storage.saveVpnRunning(true)
        updateTile(true)
    }

    private fun updateTile(runningOverride: Boolean? = null) {
        val running = runningOverride ?: AppStorage(this).load().vpnRunning
        qsTile?.apply {
            label = "Happish VPN"
            subtitle = if (running) "Подключено" else "Отключено"
            state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                icon = Icon.createWithResource(this@HappishTileService, android.R.drawable.stat_sys_download_done)
            }
            updateTile()
        }
    }

    private fun openMainActivity() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pendingIntent = PendingIntent.getActivity(this, 42, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pendingIntent)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun tileDnsAddress(preset: String, customDns: String): String = when (preset) {
        "system" -> "local"
        "cloudflare" -> "tls://1.1.1.1"
        "adguard" -> "tls://94.140.14.14"
        "custom" -> customDns.ifBlank { "tls://8.8.8.8" }
        else -> "tls://8.8.8.8"
    }
}
