package ai.arena.happish.core

import ai.arena.happish.data.AppLog
import ai.arena.happish.data.AppStorage
import ai.arena.happish.data.ProxyServer
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.result.ActivityResultLauncher
import androidx.core.content.ContextCompat

private fun resolveDnsAddress(preset: String, customDns: String): String = when (preset) {
    "system" -> "local"
    "cloudflare" -> "tls://1.1.1.1"
    "adguard" -> "tls://94.140.14.14"
    "custom" -> customDns.ifBlank { "tls://8.8.8.8" }
    else -> "tls://8.8.8.8"
}

class ConnectionController(private val context: Context) {
    fun prepareOrStart(server: ProxyServer, vpnPermissionLauncher: ActivityResultLauncher<Intent>) {
        AppLog.i("Запрос подключения: ${server.name} (${server.subtitle})")
        val prepare = VpnService.prepare(context)
        if (prepare != null) {
            AppLog.i("Нужно разрешение Android VPN")
            vpnPermissionLauncher.launch(prepare)
        } else {
            start(server)
        }
    }

    fun start(server: ProxyServer) {
        val settings = AppStorage(context).load()
        val dnsAddress = resolveDnsAddress(settings.dnsPreset, settings.customDns)
        AppLog.i("Генерация sing-box config для ${server.protocol} ${server.host}:${server.port}, DNS: $dnsAddress, route: ${settings.routeMode}")
        val config = SingBoxConfigGenerator.generate(server, dnsAddress, settings.routeMode)
        val intent = Intent(context, HappishVpnService::class.java).apply {
            action = HappishVpnService.ACTION_START
            putExtra(HappishVpnService.EXTRA_CONFIG, config)
        }
        ContextCompat.startForegroundService(context, intent)
        AppLog.i("Foreground VPN service запущен")
    }

    fun stop() {
        AppLog.i("Остановка VPN запрошена пользователем")
        context.startService(Intent(context, HappishVpnService::class.java).apply {
            action = HappishVpnService.ACTION_STOP
        })
    }
}
