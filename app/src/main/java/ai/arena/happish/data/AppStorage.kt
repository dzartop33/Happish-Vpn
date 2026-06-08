package ai.arena.happish.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class PersistedVpnState(
    val subscriptionUrl: String = "",
    val servers: List<ProxyServer> = emptyList(),
    val selectedServerId: String? = null,
    val splitTunneling: Boolean = false,
    val splitTunnelIncludeMode: Boolean = false,
    val splitTunnelPackages: Set<String> = emptySet(),
    val dnsPreset: String = "google",
    val customDns: String = "",
    val routeMode: String = "global",
    val favoriteServerIds: Set<String> = emptySet(),
    val vpnRunning: Boolean = false,
    val updateRepo: String = "",
    val autoPing: Boolean = true,
    val autoRefresh: Boolean = true,
    val lastSubscriptionUpdateAt: Long = 0L
)

class AppStorage(context: Context) {
    private val prefs: SharedPreferences = createSecurePrefs(context)

    private fun createSecurePrefs(context: Context): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "happish_secure_state",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.getOrElse {
        // Fallback keeps the app usable on devices where Android Keystore is broken/restricted.
        context.getSharedPreferences("happish_state", Context.MODE_PRIVATE)
    }

    fun load(): PersistedVpnState {
        val subscriptionUrl = prefs.getString(KEY_SUBSCRIPTION_URL, "").orEmpty()
        val selectedId = prefs.getString(KEY_SELECTED_ID, null)
        val split = prefs.getBoolean(KEY_SPLIT_TUNNELING, false)
        val splitIncludeMode = prefs.getBoolean(KEY_SPLIT_TUNNEL_INCLUDE_MODE, false)
        val splitPackages = prefs.getStringSet(KEY_SPLIT_TUNNEL_PACKAGES, emptySet()).orEmpty()
        val dnsPreset = prefs.getString(KEY_DNS_PRESET, "google").orEmpty()
        val customDns = prefs.getString(KEY_CUSTOM_DNS, "").orEmpty()
        val routeMode = prefs.getString(KEY_ROUTE_MODE, "global").orEmpty()
        val favoriteServerIds = prefs.getStringSet(KEY_FAVORITE_SERVER_IDS, emptySet()).orEmpty()
        val vpnRunning = prefs.getBoolean(KEY_VPN_RUNNING, false)
        val updateRepo = prefs.getString(KEY_UPDATE_REPO, "").orEmpty()
        val autoPing = prefs.getBoolean(KEY_AUTO_PING, true)
        val autoRefresh = prefs.getBoolean(KEY_AUTO_REFRESH, true)
        val lastUpdate = prefs.getLong(KEY_LAST_SUBSCRIPTION_UPDATE_AT, 0L)
        val rawServersJson = prefs.getString(KEY_SERVERS_RAW, "[]").orEmpty()

        val servers = runCatching {
            val array = JSONArray(rawServersJson)
            buildList {
                for (index in 0 until array.length()) {
                    val raw = array.optString(index)
                    SubscriptionParser.parseSubscription(raw).firstOrNull()?.let { add(it) }
                }
            }
        }.getOrDefault(emptyList())

        return PersistedVpnState(
            subscriptionUrl = subscriptionUrl,
            servers = servers,
            selectedServerId = selectedId,
            splitTunneling = split,
            splitTunnelIncludeMode = splitIncludeMode,
            splitTunnelPackages = splitPackages,
            dnsPreset = dnsPreset,
            customDns = customDns,
            routeMode = routeMode,
            favoriteServerIds = favoriteServerIds,
            vpnRunning = vpnRunning,
            updateRepo = updateRepo,
            autoPing = autoPing,
            autoRefresh = autoRefresh,
            lastSubscriptionUpdateAt = lastUpdate
        )
    }

    fun saveSubscriptionUrl(value: String) {
        prefs.edit().putString(KEY_SUBSCRIPTION_URL, value).apply()
    }

    fun saveServers(servers: List<ProxyServer>, selectedServerId: String?, updateTimestamp: Boolean = true) {
        val rawArray = JSONArray()
        servers.forEach { rawArray.put(it.raw) }
        val editor = prefs.edit()
            .putString(KEY_SERVERS_RAW, rawArray.toString())
            .putString(KEY_SELECTED_ID, selectedServerId)
        if (updateTimestamp) editor.putLong(KEY_LAST_SUBSCRIPTION_UPDATE_AT, System.currentTimeMillis())
        editor.apply()
    }

    fun saveSelectedServer(id: String?) {
        prefs.edit().putString(KEY_SELECTED_ID, id).apply()
    }

    fun saveSettings(splitTunneling: Boolean, autoPing: Boolean, autoRefresh: Boolean) {
        prefs.edit()
            .putBoolean(KEY_SPLIT_TUNNELING, splitTunneling)
            .putBoolean(KEY_AUTO_PING, autoPing)
            .putBoolean(KEY_AUTO_REFRESH, autoRefresh)
            .apply()
    }

    fun saveSplitTunnel(includeMode: Boolean, packages: Set<String>) {
        prefs.edit()
            .putBoolean(KEY_SPLIT_TUNNEL_INCLUDE_MODE, includeMode)
            .putStringSet(KEY_SPLIT_TUNNEL_PACKAGES, packages)
            .apply()
    }

    fun saveDns(preset: String, customDns: String) {
        prefs.edit()
            .putString(KEY_DNS_PRESET, preset)
            .putString(KEY_CUSTOM_DNS, customDns)
            .apply()
    }

    fun saveRouteMode(mode: String) {
        prefs.edit().putString(KEY_ROUTE_MODE, mode).apply()
    }

    fun saveFavoriteServerIds(ids: Set<String>) {
        prefs.edit().putStringSet(KEY_FAVORITE_SERVER_IDS, ids).apply()
    }

    fun saveVpnRunning(running: Boolean) {
        prefs.edit().putBoolean(KEY_VPN_RUNNING, running).apply()
    }

    fun saveUpdateRepo(repo: String) {
        prefs.edit().putString(KEY_UPDATE_REPO, repo).apply()
    }

    fun exportProfileJson(): String {
        val state = load()
        val serversRaw = JSONArray()
        state.servers.forEach { serversRaw.put(it.raw) }
        val splitPackages = JSONArray()
        state.splitTunnelPackages.sorted().forEach { splitPackages.put(it) }
        val favorites = JSONArray()
        state.favoriteServerIds.sorted().forEach { favorites.put(it) }
        return JSONObject()
            .put("version", 1)
            .put("subscriptionUrl", state.subscriptionUrl)
            .put("serversRaw", serversRaw)
            .put("selectedServerId", state.selectedServerId)
            .put("splitTunneling", state.splitTunneling)
            .put("splitTunnelIncludeMode", state.splitTunnelIncludeMode)
            .put("splitTunnelPackages", splitPackages)
            .put("dnsPreset", state.dnsPreset)
            .put("customDns", state.customDns)
            .put("routeMode", state.routeMode)
            .put("favoriteServerIds", favorites)
            .put("updateRepo", state.updateRepo)
            .put("autoPing", state.autoPing)
            .put("autoRefresh", state.autoRefresh)
            .put("lastSubscriptionUpdateAt", state.lastSubscriptionUpdateAt)
            .toString(2)
    }

    fun importProfileJson(jsonText: String): PersistedVpnState {
        val json = JSONObject(jsonText)
        val rawServers = json.optJSONArray("serversRaw") ?: JSONArray()
        val servers = buildList {
            for (index in 0 until rawServers.length()) {
                rawServers.optString(index).takeIf { it.isNotBlank() }?.let { raw ->
                    SubscriptionParser.parseLink(raw)?.let { add(it) }
                }
            }
        }
        val selectedId = json.optString("selectedServerId").ifBlank { servers.firstOrNull()?.id.orEmpty() }
        val splitPackages = json.optJSONArray("splitTunnelPackages").toStringSet()
        val favorites = json.optJSONArray("favoriteServerIds").toStringSet()
        val imported = PersistedVpnState(
            subscriptionUrl = json.optString("subscriptionUrl"),
            servers = servers,
            selectedServerId = selectedId.takeIf { it.isNotBlank() },
            splitTunneling = json.optBoolean("splitTunneling", false),
            splitTunnelIncludeMode = json.optBoolean("splitTunnelIncludeMode", false),
            splitTunnelPackages = splitPackages,
            dnsPreset = json.optString("dnsPreset", "google"),
            customDns = json.optString("customDns"),
            routeMode = json.optString("routeMode", "global"),
            favoriteServerIds = favorites,
            vpnRunning = false,
            updateRepo = json.optString("updateRepo"),
            autoPing = json.optBoolean("autoPing", true),
            autoRefresh = json.optBoolean("autoRefresh", true),
            lastSubscriptionUpdateAt = json.optLong("lastSubscriptionUpdateAt", 0L)
        )
        val rawArray = JSONArray()
        imported.servers.forEach { rawArray.put(it.raw) }
        prefs.edit()
            .putString(KEY_SUBSCRIPTION_URL, imported.subscriptionUrl)
            .putString(KEY_SERVERS_RAW, rawArray.toString())
            .putString(KEY_SELECTED_ID, imported.selectedServerId)
            .putBoolean(KEY_SPLIT_TUNNELING, imported.splitTunneling)
            .putBoolean(KEY_SPLIT_TUNNEL_INCLUDE_MODE, imported.splitTunnelIncludeMode)
            .putStringSet(KEY_SPLIT_TUNNEL_PACKAGES, imported.splitTunnelPackages)
            .putString(KEY_DNS_PRESET, imported.dnsPreset)
            .putString(KEY_CUSTOM_DNS, imported.customDns)
            .putString(KEY_ROUTE_MODE, imported.routeMode)
            .putStringSet(KEY_FAVORITE_SERVER_IDS, imported.favoriteServerIds)
            .putBoolean(KEY_VPN_RUNNING, false)
            .putString(KEY_UPDATE_REPO, imported.updateRepo)
            .putBoolean(KEY_AUTO_PING, imported.autoPing)
            .putBoolean(KEY_AUTO_REFRESH, imported.autoRefresh)
            .putLong(KEY_LAST_SUBSCRIPTION_UPDATE_AT, imported.lastSubscriptionUpdateAt)
            .apply()
        return imported
    }

    fun clearServers() {
        prefs.edit()
            .remove(KEY_SERVERS_RAW)
            .remove(KEY_SELECTED_ID)
            .apply()
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) {
                optString(index).takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }

    companion object {
        private const val KEY_SUBSCRIPTION_URL = "subscription_url"
        private const val KEY_SERVERS_RAW = "servers_raw"
        private const val KEY_SELECTED_ID = "selected_id"
        private const val KEY_SPLIT_TUNNELING = "split_tunneling"
        private const val KEY_SPLIT_TUNNEL_INCLUDE_MODE = "split_tunnel_include_mode"
        private const val KEY_SPLIT_TUNNEL_PACKAGES = "split_tunnel_packages"
        private const val KEY_DNS_PRESET = "dns_preset"
        private const val KEY_CUSTOM_DNS = "custom_dns"
        private const val KEY_ROUTE_MODE = "route_mode"
        private const val KEY_FAVORITE_SERVER_IDS = "favorite_server_ids"
        private const val KEY_VPN_RUNNING = "vpn_running"
        private const val KEY_UPDATE_REPO = "update_repo"
        private const val KEY_AUTO_PING = "auto_ping"
        private const val KEY_AUTO_REFRESH = "auto_refresh"
        private const val KEY_LAST_SUBSCRIPTION_UPDATE_AT = "last_subscription_update_at"
    }
}
