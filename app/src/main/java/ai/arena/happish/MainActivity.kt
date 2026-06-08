package ai.arena.happish

import ai.arena.happish.core.ConnectionController
import ai.arena.happish.data.AppLog
import ai.arena.happish.data.AppStorage
import ai.arena.happish.data.CrashReporter
import ai.arena.happish.data.ProxyServer
import ai.arena.happish.data.SubscriptionParser
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.TrafficStats
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashReporter.install(this)
        IncomingShare.emit(extractSharedText(intent))
        setContent { HappishApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        IncomingShare.emit(extractSharedText(intent))
    }
}

private object IncomingShare {
    val text = MutableStateFlow<String?>(null)
    fun emit(value: String?) {
        if (!value.isNullOrBlank()) text.value = value.trim()
    }
    fun clear() {
        text.value = null
    }
}

private fun extractSharedText(intent: Intent?): String? {
    if (intent == null) return null
    return when (intent.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_VIEW -> intent.dataString
        else -> null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HappishApp() {
    val context = LocalContext.current
    val controller = remember { ConnectionController(context) }
    val storage = remember { AppStorage(context) }
    var servers by remember { mutableStateOf<List<ProxyServer>>(emptyList()) }
    var selected by remember { mutableStateOf<ProxyServer?>(null) }
    var subUrl by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Отключено") }
    var connected by remember { mutableStateOf(false) }
    var splitTunneling by remember { mutableStateOf(false) }
    var splitIncludeMode by remember { mutableStateOf(false) }
    var splitPackages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var dnsPreset by remember { mutableStateOf("google") }
    var customDns by remember { mutableStateOf("") }
    var routeMode by remember { mutableStateOf("global") }
    var installedApps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var showAllSplitApps by remember { mutableStateOf(false) }
    var autoPing by remember { mutableStateOf(true) }
    var autoRefresh by remember { mutableStateOf(true) }
    var lastSubscriptionUpdateAt by remember { mutableStateOf(0L) }
    var delays by remember { mutableStateOf<Map<String, Long?>>(emptyMap()) }
    var pinging by remember { mutableStateOf(false) }
    var serverSearch by remember { mutableStateOf("") }
    var serverFilter by remember { mutableStateOf("all") }
    var favoriteServerIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var updateRepo by remember { mutableStateOf("") }
    var updateStatus by remember { mutableStateOf("Укажи GitHub repo в формате owner/repo") }
    var updateUrl by remember { mutableStateOf<String?>(null) }
    var checkingUpdates by remember { mutableStateOf(false) }
    var publicIpStatus by remember { mutableStateOf("IP не проверялся") }
    var checkingIp by remember { mutableStateOf(false) }
    var currentTab by remember { mutableStateOf("home") }
    var traffic by remember { mutableStateOf(TrafficSnapshot()) }
    var error by remember { mutableStateOf<String?>(null) }
    val logLines by AppLog.lines.collectAsState()
    val sharedText by IncomingShare.text.collectAsState()
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        selected?.let {
            controller.start(it)
            connected = true
            status = "Подключено к ${it.name}"
        }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    fun toggleSplitPackage(packageName: String) {
        val next = if (packageName in splitPackages) splitPackages - packageName else splitPackages + packageName
        splitPackages = next
        storage.saveSplitTunnel(splitIncludeMode, next)
        AppLog.i("Split tunneling: выбрано приложений ${next.size}")
    }

    fun startDelayTest(targetServers: List<ProxyServer>) {
        if (targetServers.isEmpty() || pinging) return
        pinging = true
        status = "Проверка серверов…"
        AppLog.i("Проверка задержки: ${targetServers.size} серверов")
        scope.launch {
            val result = measureServerDelays(targetServers)
            delays = result
            val fastest = result.entries
                .filter { it.value != null }
                .minByOrNull { it.value ?: Long.MAX_VALUE }
                ?.key
                ?.let { id -> targetServers.firstOrNull { it.id == id } }
            if (autoPing && fastest != null) {
                selected = fastest
                storage.saveSelectedServer(fastest.id)
                AppLog.i("Автовыбор: ${fastest.name}, задержка ${result[fastest.id]} ms")
            }
            val available = result.values.count { it != null }
            status = if (available > 0) "Проверка завершена: доступно $available/${targetServers.size}" else "Все серверы timeout"
            AppLog.i(status)
            pinging = false
        }
    }

    fun toggleFavorite(server: ProxyServer) {
        val next = if (server.id in favoriteServerIds) favoriteServerIds - server.id else favoriteServerIds + server.id
        favoriteServerIds = next
        storage.saveFavoriteServerIds(next)
        AppLog.i(if (server.id in next) "Добавлено в избранное: ${server.name}" else "Удалено из избранного: ${server.name}")
    }

    fun importAndSave(input: String, source: String) {
        val cleanInput = input.trim()
        if (cleanInput.isBlank()) {
            error = "Вставь ссылку или отсканируй QR"
            return
        }
        subUrl = cleanInput
        storage.saveSubscriptionUrl(cleanInput)
        error = null
        status = "Загрузка подписки…"
        AppLog.i(source)
        scope.launch {
            runCatching {
                importSubscription(cleanInput)
            }.onSuccess { list ->
                servers = list
                selected = list.firstOrNull()
                storage.saveSubscriptionUrl(cleanInput)
                storage.saveServers(list, selected?.id)
                lastSubscriptionUpdateAt = System.currentTimeMillis()
                delays = emptyMap()
                status = if (list.isEmpty()) "Серверы не найдены" else "Найдено серверов: ${list.size}"
                AppLog.i(status)
                if (list.isEmpty()) error = "Проверь ссылку: поддерживаются vmess/vless/trojan/ss/hy2"
                if (autoPing && list.isNotEmpty()) startDelayTest(list)
            }.onFailure {
                val parsed = SubscriptionParser.parseSubscription(cleanInput)
                servers = parsed
                selected = parsed.firstOrNull()
                storage.saveSubscriptionUrl(cleanInput)
                storage.saveServers(parsed, selected?.id)
                lastSubscriptionUpdateAt = System.currentTimeMillis()
                delays = emptyMap()
                status = if (parsed.isEmpty()) "Ошибка импорта" else "Импортировано из текста: ${parsed.size}"
                AppLog.i(status)
                if (parsed.isEmpty()) {
                    error = it.message ?: "Не удалось загрузить подписку"
                    AppLog.e("Ошибка импорта подписки", it)
                }
                if (autoPing && parsed.isNotEmpty()) startDelayTest(parsed)
            }
        }
    }

    val qrScannerLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents.isNullOrBlank()) {
            AppLog.w("QR-сканирование отменено")
        } else {
            AppLog.i("QR-код распознан")
            importAndSave(contents, "Импорт из QR-кода")
        }
    }

    LaunchedEffect(sharedText) {
        val value = sharedText
        if (!value.isNullOrBlank()) {
            importAndSave(value, "Импорт через Share intent")
            IncomingShare.clear()
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(connected) {
        if (!connected) {
            traffic = TrafficSnapshot()
            publicIpStatus = "IP не проверялся"
            return@LaunchedEffect
        }
        val startTime = System.currentTimeMillis()
        val baseRx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
        val baseTx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
        var lastRx = baseRx
        var lastTx = baseTx
        var lastAt = startTime
        scope.launch {
            delay(3500)
            if (connected) {
                checkingIp = true
                publicIpStatus = "Проверяю внешний IP…"
                runCatching { fetchPublicIp() }
                    .onSuccess {
                        publicIpStatus = "Внешний IP: $it"
                        AppLog.i(publicIpStatus)
                    }
                    .onFailure {
                        publicIpStatus = "Не удалось проверить IP"
                        AppLog.e("Ошибка проверки внешнего IP", it)
                    }
                checkingIp = false
            }
        }
        while (connected) {
            delay(1000)
            val now = System.currentTimeMillis()
            val rx = TrafficStats.getTotalRxBytes().coerceAtLeast(0L)
            val tx = TrafficStats.getTotalTxBytes().coerceAtLeast(0L)
            val elapsed = (now - lastAt).coerceAtLeast(1L)
            traffic = TrafficSnapshot(
                downloadedBytes = (rx - baseRx).coerceAtLeast(0L),
                uploadedBytes = (tx - baseTx).coerceAtLeast(0L),
                downloadSpeedBytes = ((rx - lastRx).coerceAtLeast(0L) * 1000L) / elapsed,
                uploadSpeedBytes = ((tx - lastTx).coerceAtLeast(0L) * 1000L) / elapsed,
                sessionMillis = now - startTime
            )
            lastRx = rx
            lastTx = tx
            lastAt = now
        }
    }

    LaunchedEffect(Unit) {
        val saved = storage.load()
        subUrl = saved.subscriptionUrl
        servers = saved.servers
        selected = saved.servers.firstOrNull { it.id == saved.selectedServerId } ?: saved.servers.firstOrNull()
        splitTunneling = saved.splitTunneling
        splitIncludeMode = saved.splitTunnelIncludeMode
        splitPackages = saved.splitTunnelPackages
        dnsPreset = saved.dnsPreset
        customDns = saved.customDns
        routeMode = saved.routeMode
        favoriteServerIds = saved.favoriteServerIds
        updateRepo = saved.updateRepo
        autoPing = saved.autoPing
        autoRefresh = saved.autoRefresh
        lastSubscriptionUpdateAt = saved.lastSubscriptionUpdateAt
        installedApps = loadLaunchableApps(context.packageManager, context.packageName)
        if (saved.servers.isNotEmpty()) status = "Загружено серверов: ${saved.servers.size}"

        val shouldAutoRefresh = saved.autoRefresh && saved.subscriptionUrl.startsWith("http") &&
            System.currentTimeMillis() - saved.lastSubscriptionUpdateAt > SUBSCRIPTION_REFRESH_INTERVAL_MS
        if (shouldAutoRefresh) {
            status = "Автообновление подписки…"
            runCatching { importSubscription(saved.subscriptionUrl) }
                .onSuccess { list ->
                    if (list.isNotEmpty()) {
                        servers = list
                        selected = list.firstOrNull { it.id == saved.selectedServerId } ?: list.firstOrNull()
                        storage.saveServers(list, selected?.id)
                        lastSubscriptionUpdateAt = System.currentTimeMillis()
                        status = "Подписка обновлена: ${list.size} серверов"
                        if (saved.autoPing) startDelayTest(list)
                    } else {
                        status = "Автообновление: серверы не найдены"
                    }
                }
                .onFailure { status = "Не удалось автообновить подписку" }
        } else if (saved.autoPing && saved.servers.isNotEmpty()) {
            startDelayTest(saved.servers)
        }
    }

    val filteredServers = servers.filter { server ->
        val query = serverSearch.trim().lowercase(Locale.getDefault())
        val matchesSearch = query.isBlank() ||
            server.name.lowercase(Locale.getDefault()).contains(query) ||
            server.host.lowercase(Locale.getDefault()).contains(query) ||
            server.protocol.lowercase(Locale.getDefault()).contains(query)
        val matchesFilter = when (serverFilter) {
            "favorites" -> server.id in favoriteServerIds
            "working" -> delays[server.id] != null
            "timeout" -> delays.containsKey(server.id) && delays[server.id] == null
            "vless", "vmess", "trojan", "shadowsocks", "hysteria2" -> server.protocol == serverFilter
            else -> true
        }
        matchesSearch && matchesFilter
    }

    val showHome = currentTab == "home"
    val showServers = currentTab == "servers"
    val showSettings = currentTab == "settings"
    val showLogs = currentTab == "logs"

    val displayedServers = if (delays.isEmpty()) {
        filteredServers.sortedWith(compareByDescending<ProxyServer> { it.id in favoriteServerIds }.thenBy { it.name })
    } else {
        filteredServers.sortedWith(
            compareByDescending<ProxyServer> { it.id in favoriteServerIds }
                .thenBy { server ->
                    when {
                        delays[server.id] != null -> delays[server.id] ?: Long.MAX_VALUE
                        delays.containsKey(server.id) -> Long.MAX_VALUE - 1
                        else -> Long.MAX_VALUE
                    }
                }
                .thenBy { it.name }
        )
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF8B7CFF), secondary = Color(0xFF00D2D3))) {
        Scaffold(
            topBar = { TopAppBar(title = { Text("Happish VPN", fontWeight = FontWeight.Bold) }) },
            containerColor = Color(0xFF0E1020)
        ) { padding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                item {
                    HeroCard(
                        status = status,
                        connected = connected,
                        selected = selected,
                        onToggle = {
                            if (connected) {
                                controller.stop()
                                connected = false
                                status = "Отключено"
                            } else {
                                val server = selected
                                if (server == null) error = "Сначала добавь подписку и выбери сервер"
                                else {
                                    error = null
                                    controller.prepareOrStart(server, permissionLauncher)
                                    connected = true
                                    status = "Подключение…"
                                }
                            }
                        }
                    )
                }

                item {
                    NavigationTabs(currentTab = currentTab, onTabSelected = { currentTab = it })
                }

                if (showHome) {
                    selected?.let { server ->
                        item { ServerDetailsCard(server = server, delayKnown = delays.containsKey(server.id), delayMs = delays[server.id]) }
                    }

                    item {
                        TrafficStatsCard(connected = connected, traffic = traffic)
                    }

                    item {
                        IpCheckCard(status = publicIpStatus, checking = checkingIp, onCheck = {
                            checkingIp = true
                            publicIpStatus = "Проверяю внешний IP…"
                            scope.launch {
                                runCatching { fetchPublicIp() }
                                    .onSuccess {
                                        publicIpStatus = "Внешний IP: $it"
                                        AppLog.i(publicIpStatus)
                                    }
                                    .onFailure {
                                        publicIpStatus = "Не удалось проверить IP"
                                        AppLog.e("Ошибка проверки внешнего IP", it)
                                    }
                                checkingIp = false
                            }
                        })
                    }

                    item {
                        ImportCard(
                        subUrl = subUrl,
                        onChange = {
                            subUrl = it
                            storage.saveSubscriptionUrl(it)
                        },
                        error = error,
                        onImport = { importAndSave(subUrl, "Импорт подписки") },
                        onPaste = {
                            val clipText = readClipboardText(context)
                            if (clipText.isBlank()) {
                                error = "Буфер обмена пуст"
                                AppLog.w("Буфер обмена пуст")
                            } else {
                                importAndSave(clipText, "Импорт из буфера обмена")
                            }
                        },
                        onQrScan = {
                            qrScannerLauncher.launch(
                                ScanOptions()
                                    .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                    .setPrompt("Наведи камеру на QR-код подписки или сервера")
                                    .setBeepEnabled(false)
                                    .setOrientationLocked(false)
                            )
                        }
                    )
                    }
                }

                if (showSettings) {
                item {
                    KillSwitchCard(
                        onOpenVpnSettings = {
                            AppLog.i("Открываю настройки VPN Android для Always-on/Kill switch")
                            context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                        }
                    )
                }

                item {
                    UpdateCheckerCard(
                        repo = updateRepo,
                        status = updateStatus,
                        releaseUrl = updateUrl,
                        checking = checkingUpdates,
                        onRepoChange = {
                            updateRepo = it
                            updateUrl = null
                            storage.saveUpdateRepo(it)
                        },
                        onCheck = {
                            val repo = updateRepo.trim()
                            if (!repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
                                updateStatus = "Неверный формат. Нужно owner/repo"
                                AppLog.w("Проверка обновлений: неверный repo")
                            } else {
                                checkingUpdates = true
                                updateStatus = "Проверяю GitHub Releases…"
                                scope.launch {
                                    runCatching { checkLatestRelease(repo) }
                                        .onSuccess { release ->
                                            updateUrl = release.htmlUrl
                                            updateStatus = "Последний релиз: ${release.tagName}${release.name.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}"
                                            AppLog.i("Проверка обновлений: $updateStatus")
                                        }
                                        .onFailure {
                                            updateStatus = "Не удалось проверить обновления"
                                            AppLog.e("Ошибка проверки обновлений", it)
                                        }
                                    checkingUpdates = false
                                }
                            }
                        },
                        onOpenRelease = {
                            updateUrl?.let { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) }
                        }
                    )
                }

                item {
                    ProfileBackupCard(
                        onExport = {
                            copyTextToClipboard(context, "Happish VPN profile", storage.exportProfileJson())
                            AppLog.i("Профиль настроек скопирован в буфер обмена")
                        },
                        onImport = {
                            val json = readClipboardText(context)
                            runCatching { storage.importProfileJson(json) }
                                .onSuccess { imported ->
                                    subUrl = imported.subscriptionUrl
                                    servers = imported.servers
                                    selected = imported.servers.firstOrNull { it.id == imported.selectedServerId } ?: imported.servers.firstOrNull()
                                    splitTunneling = imported.splitTunneling
                                    splitIncludeMode = imported.splitTunnelIncludeMode
                                    splitPackages = imported.splitTunnelPackages
                                    dnsPreset = imported.dnsPreset
                                    customDns = imported.customDns
                                    routeMode = imported.routeMode
                                    favoriteServerIds = imported.favoriteServerIds
                                    updateRepo = imported.updateRepo
                                    autoPing = imported.autoPing
                                    autoRefresh = imported.autoRefresh
                                    lastSubscriptionUpdateAt = imported.lastSubscriptionUpdateAt
                                    delays = emptyMap()
                                    status = "Профиль импортирован: ${imported.servers.size} серверов"
                                    AppLog.i(status)
                                }
                                .onFailure {
                                    error = "В буфере нет корректного профиля Happish"
                                    AppLog.e("Ошибка импорта профиля", it)
                                }
                        }
                    )
                }

                item {
                    SettingsCard(
                        split = splitTunneling,
                        onSplit = {
                            splitTunneling = it
                            storage.saveSettings(splitTunneling = it, autoPing = autoPing, autoRefresh = autoRefresh)
                            AppLog.i(if (it) "Split tunneling включён" else "Split tunneling выключен")
                        },
                        ping = autoPing,
                        onPing = {
                            autoPing = it
                            storage.saveSettings(splitTunneling = splitTunneling, autoPing = it, autoRefresh = autoRefresh)
                            if (it && servers.isNotEmpty()) startDelayTest(servers)
                        },
                        autoRefresh = autoRefresh,
                        onAutoRefresh = {
                            autoRefresh = it
                            storage.saveSettings(splitTunneling = splitTunneling, autoPing = autoPing, autoRefresh = it)
                        },
                        lastUpdateAt = lastSubscriptionUpdateAt
                    )
                }

                item {
                    RoutingSettingsCard(
                        selectedMode = routeMode,
                        onModeSelected = {
                            routeMode = it
                            storage.saveRouteMode(it)
                            AppLog.i("Routing mode: ${routeModeLabel(it)}")
                        }
                    )
                }

                item {
                    DnsSettingsCard(
                        selectedPreset = dnsPreset,
                        customDns = customDns,
                        onPresetSelected = {
                            dnsPreset = it
                            storage.saveDns(it, customDns)
                            AppLog.i("DNS preset: ${dnsLabel(it)}")
                        },
                        onCustomDnsChange = {
                            customDns = it
                            storage.saveDns(dnsPreset, it)
                        }
                    )
                }

                if (splitTunneling) {
                    item {
                        SplitTunnelingCard(
                            apps = installedApps,
                            selectedPackages = splitPackages,
                            includeMode = splitIncludeMode,
                            showAll = showAllSplitApps,
                            onShowAllChange = { showAllSplitApps = it },
                            onModeChange = {
                                splitIncludeMode = it
                                storage.saveSplitTunnel(it, splitPackages)
                                AppLog.i(if (it) "Split mode: только выбранные через VPN" else "Split mode: выбранные мимо VPN")
                            },
                            onTogglePackage = { toggleSplitPackage(it) }
                        )
                    }
                }
                }

                if (showLogs) {
                item {
                    LogsCard(
                        lines = logLines,
                        onCopy = {
                            copyLogsToClipboard(context)
                            AppLog.i("Логи скопированы в буфер обмена")
                        },
                        onShare = { shareLogs(context) },
                        onClear = { AppLog.clear() }
                    )
                }
                item {
                    CrashReportCard(
                        lastCrash = CrashReporter.lastCrash(context),
                        onCopy = {
                            CrashReporter.copy(context)
                            AppLog.i("Crash report скопирован")
                        },
                        onShare = { CrashReporter.share(context) },
                        onClear = {
                            CrashReporter.clear(context)
                            AppLog.i("Crash report очищен")
                        }
                    )
                }
                }

                if (showServers) {
                item {
                    ServerSearchFilterCard(
                        query = serverSearch,
                        selectedFilter = serverFilter,
                        totalCount = servers.size,
                        visibleCount = displayedServers.size,
                        onQueryChange = { serverSearch = it },
                        onFilterChange = { serverFilter = it }
                    )
                }

                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Серверы", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Button(
                            onClick = { startDelayTest(servers) },
                            enabled = servers.isNotEmpty() && !pinging,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(if (pinging) "Проверка…" else "Пинг")
                        }
                    }
                }
                items(displayedServers) { server ->
                    ServerRow(
                        server = server,
                        active = selected?.id == server.id,
                        delayKnown = delays.containsKey(server.id),
                        delayMs = delays[server.id],
                        favorite = server.id in favoriteServerIds,
                        onFavoriteClick = { toggleFavorite(server) }
                    ) {
                        selected = server
                        storage.saveSelectedServer(server.id)
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun NavigationTabs(currentTab: String, onTabSelected: (String) -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Разделы", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            navTabs.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { tab ->
                        val selected = currentTab == tab.id
                        Button(
                            onClick = { onTabSelected(tab.id) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = if (selected) Color(0xFF6C5CE7) else Color(0xFF252A4A))
                        ) { Text(tab.label) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun HeroCard(status: String, connected: Boolean, selected: ProxyServer?, onToggle: () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .background(Brush.linearGradient(listOf(Color(0xFF6C5CE7), Color(0xFF00D2D3))))
                .padding(24.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Text(status, color = Color.White.copy(alpha = .9f), fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text(selected?.name ?: "Выбери сервер", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = onToggle,
                    shape = CircleShape,
                    modifier = Modifier.size(148.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = if (connected) Color(0xFFFF7675) else Color.White)
                ) {
                    Text(if (connected) "STOP" else "GO", color = if (connected) Color.White else Color(0xFF2D2A57), fontSize = 28.sp, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(20.dp))
                Text(selected?.subtitle ?: "VLESS / VMess / Trojan / Shadowsocks / Hysteria2", color = Color.White.copy(alpha = .86f))
            }
        }
    }
}

@Composable
private fun ServerDetailsCard(server: ProxyServer, delayKnown: Boolean, delayMs: Long?) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Информация о сервере", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                DelayBadge(delayKnown = delayKnown, delayMs = delayMs)
            }
            InfoRow("Название", server.name)
            InfoRow("Протокол", server.protocol.uppercase())
            InfoRow("Host", server.host)
            InfoRow("Port", server.port.toString())
            server.uuid?.takeIf { it.isNotBlank() }?.let { InfoRow("UUID", maskSecret(it)) }
            server.method?.takeIf { it.isNotBlank() }?.let { InfoRow("Method", it) }
            val importantParams = listOf("security", "type", "network", "sni", "host", "path", "fp", "pbk", "sid", "serviceName", "mode")
            importantParams.forEach { key ->
                server.params[key]?.takeIf { it.isNotBlank() }?.let { value ->
                    InfoRow(key, if (key in setOf("pbk", "sid")) maskSecret(value) else value)
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, color = Color.White.copy(alpha = .55f), fontSize = 12.sp, modifier = Modifier.width(92.dp))
        Text(value, color = Color.White, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TrafficStatsCard(connected: Boolean, traffic: TrafficSnapshot) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Статистика", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text(if (connected) formatDuration(traffic.sessionMillis) else "00:00:00", color = Color.White.copy(alpha = .65f), fontWeight = FontWeight.SemiBold)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("↓ Скачано", formatBytes(traffic.downloadedBytes), Modifier.weight(1f))
                StatBox("↑ Отдано", formatBytes(traffic.uploadedBytes), Modifier.weight(1f))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatBox("↓ Скорость", "${formatBytes(traffic.downloadSpeedBytes)}/s", Modifier.weight(1f))
                StatBox("↑ Скорость", "${formatBytes(traffic.uploadSpeedBytes)}/s", Modifier.weight(1f))
            }
            Text(
                "Статистика считается по системным счётчикам Android и нужна для ориентировочной оценки сессии.",
                color = Color.White.copy(alpha = .45f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun StatBox(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = Color(0xFF101326)) {
        Column(Modifier.padding(12.dp)) {
            Text(title, color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Text(value, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun IpCheckCard(status: String, checking: Boolean, onCheck: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Проверка IP", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Text(status, color = Color.White.copy(alpha = .62f), fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Button(onClick = onCheck, enabled = !checking, shape = RoundedCornerShape(14.dp)) {
                Text(if (checking) "…" else "Проверить")
            }
        }
    }
}

@Composable
private fun ImportCard(
    subUrl: String,
    onChange: (String) -> Unit,
    error: String?,
    onImport: () -> Unit,
    onPaste: () -> Unit,
    onQrScan: () -> Unit
) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Импорт подписки", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            OutlinedTextField(
                value = subUrl,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("https://… или vmess:// vless:// trojan:// ss://") },
                minLines = 2,
                shape = RoundedCornerShape(18.dp)
            )
            AnimatedVisibility(error != null) { Text(error.orEmpty(), color = Color(0xFFFF7675)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onImport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text("Найти")
                }
                Button(onClick = onPaste, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text("Вставить")
                }
                Button(onClick = onQrScan, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text("QR")
                }
            }
        }
    }
}

@Composable
private fun KillSwitchCard(onOpenVpnSettings: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Kill switch", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Для максимальной защиты включи системные параметры Android: Always-on VPN и Block connections without VPN. Приложение не может включить их молча, но может открыть нужный экран.",
                color = Color.White.copy(alpha = .58f),
                fontSize = 12.sp
            )
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFF101326)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("1. Выбери Happish VPN", color = Color.White.copy(alpha = .78f), fontSize = 12.sp)
                    Text("2. Включи Always-on VPN", color = Color.White.copy(alpha = .78f), fontSize = 12.sp)
                    Text("3. Включи Block connections without VPN", color = Color.White.copy(alpha = .78f), fontSize = 12.sp)
                }
            }
            Button(onClick = onOpenVpnSettings, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Text("Открыть настройки VPN")
            }
        }
    }
}

@Composable
private fun UpdateCheckerCard(
    repo: String,
    status: String,
    releaseUrl: String?,
    checking: Boolean,
    onRepoChange: (String) -> Unit,
    onCheck: () -> Unit,
    onOpenRelease: () -> Unit
) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Обновления", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("Проверка последнего релиза в GitHub Releases. Укажи репозиторий, где будут публиковаться APK.", color = Color.White.copy(alpha = .58f), fontSize = 12.sp)
            OutlinedTextField(
                value = repo,
                onValueChange = onRepoChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("owner/repository") },
                shape = RoundedCornerShape(18.dp),
                singleLine = true
            )
            Text(status, color = Color.White.copy(alpha = .72f), fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCheck, enabled = !checking, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text(if (checking) "Проверка…" else "Проверить")
                }
                Button(onClick = onOpenRelease, enabled = releaseUrl != null, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text("Открыть")
                }
            }
        }
    }
}

@Composable
private fun ProfileBackupCard(onExport: () -> Unit, onImport: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Профиль", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Экспорт сохраняет подписку, серверы, DNS, маршрутизацию, split tunneling и избранное в JSON. Импорт читает JSON из буфера обмена.",
                color = Color.White.copy(alpha = .58f),
                fontSize = 12.sp
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onExport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text("Экспорт")
                }
                Button(onClick = onImport, modifier = Modifier.weight(1f), shape = RoundedCornerShape(16.dp)) {
                    Text("Импорт")
                }
            }
        }
    }
}

@Composable
private fun SettingsCard(
    split: Boolean,
    onSplit: (Boolean) -> Unit,
    ping: Boolean,
    onPing: (Boolean) -> Unit,
    autoRefresh: Boolean,
    onAutoRefresh: (Boolean) -> Unit,
    lastUpdateAt: Long
) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp)) {
            Text("Настройки", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            SettingRow("Автообновление подписки", lastUpdateLabel(lastUpdateAt), autoRefresh, onAutoRefresh)
            Divider(color = Color.White.copy(alpha = .08f))
            SettingRow("Автовыбор быстрого сервера", "После проверки выбирает сервер с минимальной задержкой", ping, onPing)
            Divider(color = Color.White.copy(alpha = .08f))
            SettingRow("Split tunneling", "Исключения приложений из VPN", split, onSplit)
        }
    }
}

@Composable
private fun ServerSearchFilterCard(
    query: String,
    selectedFilter: String,
    totalCount: Int,
    visibleCount: Int,
    onQueryChange: (String) -> Unit,
    onFilterChange: (String) -> Unit
) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Поиск и фильтры", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Text("$visibleCount/$totalCount", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Поиск: Germany, vless, example.com…") },
                shape = RoundedCornerShape(18.dp),
                singleLine = true
            )
            serverFilterOptions.chunked(3).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { option ->
                        val selected = selectedFilter == option.id
                        Button(
                            onClick = { onFilterChange(option.id) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) Color(0xFF6C5CE7) else Color(0xFF252A4A)
                            )
                        ) {
                            Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun RoutingSettingsCard(
    selectedMode: String,
    onModeSelected: (String) -> Unit
) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Маршрутизация", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Выбери, какой трафик отправлять через VPN. Режим применяется при следующем подключении.",
                color = Color.White.copy(alpha = .58f),
                fontSize = 12.sp
            )
            routeOptions.forEach { option ->
                val selected = selectedMode == option.id
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onModeSelected(option.id) },
                    shape = RoundedCornerShape(16.dp),
                    color = if (selected) Color(0xFF282D55) else Color(0xFF15182B)
                ) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(if (selected) Color(0xFF00D2D3) else Color(0xFF303656)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(if (selected) "✓" else option.label.take(1), color = Color.White, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(option.label, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(option.description, color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DnsSettingsCard(
    selectedPreset: String,
    customDns: String,
    onPresetSelected: (String) -> Unit,
    onCustomDnsChange: (String) -> Unit
) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("DNS", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "DNS применяется при следующем подключении. Можно выбрать системный DNS, DoT-провайдера или свой адрес.",
                color = Color.White.copy(alpha = .58f),
                fontSize = 12.sp
            )
            dnsOptions.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { option ->
                        val selected = selectedPreset == option.id
                        Button(
                            onClick = { onPresetSelected(option.id) },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (selected) Color(0xFF6C5CE7) else Color(0xFF252A4A)
                            )
                        ) {
                            Text(option.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            val active = dnsOptions.firstOrNull { it.id == selectedPreset } ?: dnsOptions.first()
            Text("Активный DNS: ${if (selectedPreset == "custom") customDns.ifBlank { "не задан" } else active.address}", color = Color.White.copy(alpha = .72f), fontSize = 12.sp)
            AnimatedVisibility(selectedPreset == "custom") {
                OutlinedTextField(
                    value = customDns,
                    onValueChange = onCustomDnsChange,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("tls://1.1.1.1 или https://dns.google/dns-query") },
                    shape = RoundedCornerShape(18.dp),
                    singleLine = true
                )
            }
        }
    }
}

@Composable
private fun SplitTunnelingCard(
    apps: List<InstalledApp>,
    selectedPackages: Set<String>,
    includeMode: Boolean,
    showAll: Boolean,
    onShowAllChange: (Boolean) -> Unit,
    onModeChange: (Boolean) -> Unit,
    onTogglePackage: (String) -> Unit
) {
    val visibleApps = if (showAll) apps else apps.take(8)
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Split tunneling", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Выбрано приложений: ${selectedPackages.size}. Режим применяется при следующем подключении.",
                color = Color.White.copy(alpha = .58f),
                fontSize = 12.sp
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Только выбранные через VPN", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (includeMode) "Остальные приложения будут напрямую" else "Сейчас выбранные приложения идут мимо VPN",
                        color = Color.White.copy(alpha = .55f),
                        fontSize = 12.sp
                    )
                }
                Switch(checked = includeMode, onCheckedChange = onModeChange)
            }
            Divider(color = Color.White.copy(alpha = .08f))
            if (apps.isEmpty()) {
                Text("Приложения не найдены", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
            } else {
                visibleApps.forEach { app ->
                    AppSplitRow(
                        app = app,
                        checked = app.packageName in selectedPackages,
                        onToggle = { onTogglePackage(app.packageName) }
                    )
                }
                if (apps.size > 8) {
                    Button(onClick = { onShowAllChange(!showAll) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                        Text(if (showAll) "Свернуть" else "Показать все приложения (${apps.size})")
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSplitRow(app: InstalledApp, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(if (checked) Color(0xFF00D2D3) else Color(0xFF303656)),
            contentAlignment = Alignment.Center
        ) {
            Text(app.label.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(app.packageName, color = Color.White.copy(alpha = .45f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun LogsCard(lines: List<String>, onCopy: () -> Unit, onShare: () -> Unit, onClear: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Логи", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                Button(onClick = onCopy, enabled = lines.isNotEmpty(), shape = RoundedCornerShape(14.dp)) {
                    Text("Копия")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onShare, enabled = lines.isNotEmpty(), shape = RoundedCornerShape(14.dp)) {
                    Text("Share")
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = onClear, enabled = lines.isNotEmpty(), shape = RoundedCornerShape(14.dp)) {
                    Text("Очистить")
                }
            }
            if (lines.isEmpty()) {
                Text("Здесь появятся события импорта, проверки серверов и подключения", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = .18f), RoundedCornerShape(16.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    lines.takeLast(8).forEach { line ->
                        Text(line, color = logLineColor(line), fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun logLineColor(line: String): Color = when {
    "ERROR" in line -> Color(0xFFFF7675)
    "WARN" in line -> Color(0xFFFFD166)
    else -> Color.White.copy(alpha = .78f)
}

@Composable
private fun CrashReportCard(lastCrash: String, onCopy: () -> Unit, onShare: () -> Unit, onClear: () -> Unit) {
    Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF181B33))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Crash reporting", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            if (lastCrash.isBlank()) {
                Text("Сохранённых crash report нет. Если приложение упадёт, отчёт появится здесь после следующего запуска.", color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
            } else {
                Text(lastCrash.lines().take(6).joinToString("\n"), color = Color(0xFFFFD166), fontSize = 11.sp, maxLines = 6, overflow = TextOverflow.Ellipsis)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onCopy, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Копия") }
                    Button(onClick = onShare, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Share") }
                    Button(onClick = onClear, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp)) { Text("Очистить") }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Color.White.copy(alpha = .55f), fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChecked)
    }
}

@Composable
private fun ServerRow(
    server: ProxyServer,
    active: Boolean,
    delayKnown: Boolean,
    delayMs: Long?,
    favorite: Boolean,
    onFavoriteClick: () -> Unit,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        color = if (active) Color(0xFF282D55) else Color(0xFF15182B)
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (active) Color(0xFF00D2D3) else Color(0xFF303656)), contentAlignment = Alignment.Center) {
                Text(server.protocol.take(1).uppercase(), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(server.name, color = Color.White, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(server.subtitle, color = Color.White.copy(alpha = .55f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            DelayBadge(delayKnown = delayKnown, delayMs = delayMs)
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (favorite) "★" else "☆",
                color = if (favorite) Color(0xFFFFD166) else Color.White.copy(alpha = .42f),
                fontWeight = FontWeight.Black,
                fontSize = 24.sp,
                modifier = Modifier.clickable(onClick = onFavoriteClick).padding(horizontal = 4.dp)
            )
            Spacer(Modifier.width(4.dp))
            if (active) Text("✓", color = Color(0xFF00D2D3), fontWeight = FontWeight.Black, fontSize = 22.sp)
        }
    }
}

@Composable
private fun DelayBadge(delayKnown: Boolean, delayMs: Long?) {
    val (label, color) = when {
        !delayKnown -> "—" to Color.White.copy(alpha = .35f)
        delayMs == null -> "timeout" to Color(0xFFFF7675)
        delayMs < 250 -> "${delayMs} ms" to Color(0xFF00D2D3)
        delayMs < 800 -> "${delayMs} ms" to Color(0xFFFFD166)
        else -> "${delayMs} ms" to Color(0xFFFF7675)
    }
    Surface(shape = RoundedCornerShape(999.dp), color = color.copy(alpha = .16f)) {
        Text(label, color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

private data class InstalledApp(
    val label: String,
    val packageName: String
)

private data class TrafficSnapshot(
    val downloadedBytes: Long = 0L,
    val uploadedBytes: Long = 0L,
    val downloadSpeedBytes: Long = 0L,
    val uploadSpeedBytes: Long = 0L,
    val sessionMillis: Long = 0L
)

private data class ReleaseInfo(
    val tagName: String,
    val name: String,
    val htmlUrl: String
)

private data class RouteOption(
    val id: String,
    val label: String,
    val description: String
)

private data class NavTab(
    val id: String,
    val label: String
)

private data class ServerFilterOption(
    val id: String,
    val label: String
)

private data class DnsOption(
    val id: String,
    val label: String,
    val address: String
)

private val navTabs = listOf(
    NavTab("home", "Главная"),
    NavTab("servers", "Серверы"),
    NavTab("settings", "Настройки"),
    NavTab("logs", "Логи")
)

private val routeOptions = listOf(
    RouteOption("global", "Global", "Весь интернет-трафик идёт через VPN"),
    RouteOption("bypass_lan", "Bypass LAN", "Локальная сеть и приватные IP идут напрямую"),
    RouteOption("rule", "Rule-based", "Базовые правила: приватные сети напрямую, остальное через VPN"),
    RouteOption("direct", "Direct", "Тестовый режим: трафик идёт напрямую без прокси")
)

private fun routeModeLabel(id: String): String = routeOptions.firstOrNull { it.id == id }?.label ?: id

private val serverFilterOptions = listOf(
    ServerFilterOption("all", "Все"),
    ServerFilterOption("favorites", "★"),
    ServerFilterOption("working", "Рабочие"),
    ServerFilterOption("timeout", "Timeout"),
    ServerFilterOption("vless", "VLESS"),
    ServerFilterOption("vmess", "VMess"),
    ServerFilterOption("trojan", "Trojan"),
    ServerFilterOption("shadowsocks", "SS"),
    ServerFilterOption("hysteria2", "Hy2")
)

private val dnsOptions = listOf(
    DnsOption("system", "System", "local"),
    DnsOption("cloudflare", "Cloudflare", "tls://1.1.1.1"),
    DnsOption("google", "Google", "tls://8.8.8.8"),
    DnsOption("adguard", "AdGuard", "tls://94.140.14.14"),
    DnsOption("custom", "Custom", "свой DNS")
)

private fun dnsLabel(id: String): String = dnsOptions.firstOrNull { it.id == id }?.label ?: id

private const val SUBSCRIPTION_REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1000L
private const val MAX_SUBSCRIPTION_BYTES = 2_000_000
private const val MAX_IMPORTED_SERVERS = 500
private const val DELAY_TEST_TIMEOUT_MS = 3_500
private const val DELAY_TEST_BATCH_SIZE = 16

private suspend fun measureServerDelays(servers: List<ProxyServer>): Map<String, Long?> {
    val result = linkedMapOf<String, Long?>()
    servers.chunked(DELAY_TEST_BATCH_SIZE).forEach { batch ->
        val batchResult = coroutineScope {
            batch.map { server ->
                async(Dispatchers.IO) { server.id to measureTcpDelay(server) }
            }.awaitAll()
        }
        result.putAll(batchResult)
    }
    return result
}

private fun measureTcpDelay(server: ProxyServer): Long? = runCatching {
    val start = System.nanoTime()
    Socket().use { socket ->
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(server.host, server.port), DELAY_TEST_TIMEOUT_MS)
    }
    ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
}.getOrNull()

private suspend fun importSubscription(input: String): List<ProxyServer> {
    require(input.length <= MAX_SUBSCRIPTION_BYTES) { "Слишком большая подписка" }
    val body = fetchText(input)
    require(body.length <= MAX_SUBSCRIPTION_BYTES) { "Слишком большая подписка" }
    return SubscriptionParser.parseSubscription(body.ifBlank { input }).take(MAX_IMPORTED_SERVERS)
}

private suspend fun fetchPublicIp(): String = withContext(Dispatchers.IO) {
    val connection = (URL("https://api.ipify.org?format=json").openConnection() as HttpURLConnection).apply {
        connectTimeout = 10_000
        readTimeout = 12_000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "HappishVPN/0.1 Android")
    }
    val body = connection.inputStream.bufferedReader().use { it.readText() }
    JSONObject(body).optString("ip", body).ifBlank { body }
}

private suspend fun checkLatestRelease(repo: String): ReleaseInfo = withContext(Dispatchers.IO) {
    val connection = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        requestMethod = "GET"
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "HappishVPN/0.1 Android")
    }
    val body = if (connection.responseCode in 200..299) {
        connection.inputStream.bufferedReader().use { it.readText() }
    } else {
        connection.errorStream?.bufferedReader()?.use { it.readText() }
        error("GitHub API error: HTTP ${connection.responseCode}")
    }
    val json = JSONObject(body)
    ReleaseInfo(
        tagName = json.optString("tag_name", "unknown"),
        name = json.optString("name"),
        htmlUrl = json.optString("html_url", "https://github.com/$repo/releases")
    )
}

private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble().coerceAtLeast(0.0)
    var unit = 0
    while (value >= 1024.0 && unit < units.lastIndex) {
        value /= 1024.0
        unit++
    }
    return if (unit == 0) "${value.toLong()} ${units[unit]}" else "${"%.1f".format(Locale.US, value)} ${units[unit]}"
}

private fun formatDuration(millis: Long): String {
    val totalSeconds = millis / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return "%02d:%02d:%02d".format(Locale.US, hours, minutes, seconds)
}

private fun maskSecret(value: String): String {
    if (value.length <= 10) return "••••"
    return value.take(6) + "…" + value.takeLast(4)
}

private fun readClipboardText(context: Context): String {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return clipboard.primaryClip
        ?.takeIf { it.itemCount > 0 }
        ?.getItemAt(0)
        ?.coerceToText(context)
        ?.toString()
        .orEmpty()
        .trim()
}

private fun copyTextToClipboard(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun copyLogsToClipboard(context: Context) {
    copyTextToClipboard(context, "Happish VPN logs", AppLog.allText())
}

private fun shareLogs(context: Context) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, "Happish VPN logs")
        putExtra(Intent.EXTRA_TEXT, AppLog.allText())
    }
    context.startActivity(Intent.createChooser(intent, "Поделиться логами"))
}

private suspend fun loadLaunchableApps(packageManager: PackageManager, ownPackage: String): List<InstalledApp> = withContext(Dispatchers.IO) {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    packageManager.queryIntentActivities(intent, 0)
        .mapNotNull { resolveInfo ->
            val activityInfo = resolveInfo.activityInfo ?: return@mapNotNull null
            val packageName = activityInfo.packageName ?: return@mapNotNull null
            if (packageName == ownPackage) return@mapNotNull null
            InstalledApp(
                label = resolveInfo.loadLabel(packageManager)?.toString()?.ifBlank { packageName } ?: packageName,
                packageName = packageName
            )
        }
        .distinctBy { it.packageName }
        .sortedBy { it.label.lowercase(Locale.getDefault()) }
}

private fun lastUpdateLabel(timestamp: Long): String {
    if (timestamp <= 0L) return "Обновлять при запуске, если подписка устарела"
    val formatted = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(timestamp))
    return "Последнее обновление: $formatted"
}

private suspend fun fetchText(input: String): String = withContext(Dispatchers.IO) {
    if (!input.startsWith("http")) return@withContext input
    val connection = (URL(input).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 20_000
        requestMethod = "GET"
        setRequestProperty("User-Agent", "HappishVPN/0.1 Android")
    }
    connection.inputStream.bufferedReader().use { reader ->
        val builder = StringBuilder()
        val buffer = CharArray(8192)
        while (true) {
            val read = reader.read(buffer)
            if (read <= 0) break
            builder.append(buffer, 0, read)
            if (builder.length > MAX_SUBSCRIPTION_BYTES) error("Слишком большая подписка")
        }
        builder.toString()
    }
}
